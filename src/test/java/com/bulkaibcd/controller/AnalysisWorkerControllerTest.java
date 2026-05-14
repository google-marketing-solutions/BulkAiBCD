package com.bulkaibcd.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.controller.AnalysisWorkerController.TaskRequest;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.CloudTasksService;
import com.bulkaibcd.service.GeminiService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class AnalysisWorkerControllerTest {

  @Mock VideoMetadataRepository videoMetadataRepository;
  @Mock VideoInputRepository videoInputRepository;
  @Mock AnalysisRequestRepository analysisRequestRepository;
  @Mock GeminiService geminiService;
  @Mock CloudTasksService cloudTasksService;

  @InjectMocks AnalysisWorkerController controller;

  // --- A: Prompt resolution -------------------------------------------------

  @Test
  void allFivePromptKeysResolve() throws Exception {
    for (String key : List.of("A_ATTRACT", "B_BRAND", "C_CONNECT", "D_DIRECT", "ASSET_NAME")) {
      TaskRequest request = new TaskRequest();
      request.setPromptType(key);
      request.setAnalysisId("a");
      request.setVideoId("v");
      request.setVideoUri("gs://bucket/video.mp4");

      when(geminiService.callGemini(anyString(), anyString(), any()))
          .thenReturn("result-for-" + key);
      when(videoMetadataRepository.findById(anyString())).thenReturn(Mono.empty());
      com.bulkaibcd.model.VideoMetadataEntity savedPlaceholder =
          com.bulkaibcd.model.VideoMetadataEntity.builder().id("saved").build();
      when(videoMetadataRepository.save(any())).thenReturn(Mono.just(savedPlaceholder));
      // findByAnalysisId returns one still-processing video so checkAnalysisCompletion
      // short-circuits without needing to hit analysisRequestRepository (which we don't care
      // about in this prompt-resolution test).
      when(videoMetadataRepository.findByAnalysisId(anyString()))
          .thenReturn(
              Flux.just(
                  com.bulkaibcd.model.VideoMetadataEntity.builder()
                      .id("placeholder")
                      .analysisId("a")
                      .status("PROCESSING")
                      .build()));

      Mono<ResponseEntity<String>> result = controller.fetchMetadata(request);
      StepVerifier.create(result)
          .assertNext(resp -> assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue())
          .verifyComplete();
    }
  }

  @Test
  void unknownPromptTypeReturns400() {
    TaskRequest request = new TaskRequest();
    request.setPromptType("NOT_A_REAL_KEY");
    request.setAnalysisId("a");
    request.setVideoId("v");

    StepVerifier.create(controller.fetchMetadata(request))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode().value()).isEqualTo(400);
              assertThat(resp.getBody()).contains("Unknown prompt type");
            })
        .verifyComplete();
  }

  // --- B: Fan-in orchestration ---------------------------------------------

  @Test
  void twoOfThreeVideosDoneLeavesParentProcessing() throws Exception {
    VideoMetadataEntity done1 =
        VideoMetadataEntity.builder().id("1").analysisId("A").videoId("v1").status("COMPLETED").build();
    VideoMetadataEntity done2 =
        VideoMetadataEntity.builder().id("2").analysisId("A").videoId("v2").status("COMPLETED").build();
    VideoMetadataEntity pending =
        VideoMetadataEntity.builder().id("3").analysisId("A").videoId("v3").status("PROCESSING").build();

    when(videoMetadataRepository.findByAnalysisId("A"))
        .thenReturn(Flux.just(done1, done2, pending));

    Mono<Void> result = invokeCheckCompletion(controller, "A");

    StepVerifier.create(result).verifyComplete();
    verify(analysisRequestRepository, never()).findById(anyString());
    verify(analysisRequestRepository, never()).save(any());
  }

  @Test
  void threeOfThreeVideosDonePromotesParentToCompleted() throws Exception {
    VideoMetadataEntity d1 =
        VideoMetadataEntity.builder().id("1").analysisId("A").status("COMPLETED").build();
    VideoMetadataEntity d2 =
        VideoMetadataEntity.builder().id("2").analysisId("A").status("COMPLETED").build();
    VideoMetadataEntity d3 =
        VideoMetadataEntity.builder().id("3").analysisId("A").status("COMPLETED").build();
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("A").analysisStatus("PROCESSING").build();

    when(videoMetadataRepository.findByAnalysisId("A")).thenReturn(Flux.just(d1, d2, d3));
    when(analysisRequestRepository.findById("A")).thenReturn(Mono.just(parent));
    when(analysisRequestRepository.save(any()))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    Mono<Void> result = invokeCheckCompletion(controller, "A");
    StepVerifier.create(result).verifyComplete();

    ArgumentCaptor<AnalysisRequestEntity> captor =
        ArgumentCaptor.forClass(AnalysisRequestEntity.class);
    verify(analysisRequestRepository).save(captor.capture());
    assertThat(captor.getValue().getAnalysisStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void emptyVideoListDoesNotPromoteParent() throws Exception {
    // The controller now guards against promoting a parent when the video list
    // is empty (previously it would, because Stream.allMatch on empty is true).
    when(videoMetadataRepository.findByAnalysisId("E")).thenReturn(Flux.empty());

    StepVerifier.create(invokeCheckCompletion(controller, "E")).verifyComplete();
    verify(analysisRequestRepository, never()).save(any());
  }

  // --- Test utilities -------------------------------------------------------

  // checkAnalysisCompletion is private; reflect to avoid widening visibility just for tests.
  @SuppressWarnings("unchecked")
  private static Mono<Void> invokeCheckCompletion(AnalysisWorkerController c, String analysisId)
      throws Exception {
    Method m =
        AnalysisWorkerController.class.getDeclaredMethod("checkAnalysisCompletion", String.class);
    m.setAccessible(true);
    return (Mono<Void>) m.invoke(c, analysisId);
  }
}
