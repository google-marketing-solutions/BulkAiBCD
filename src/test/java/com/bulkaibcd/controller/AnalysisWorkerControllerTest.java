package com.bulkaibcd.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.controller.AnalysisWorkerController.TaskRequest;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.VideoInputEntity;
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
      when(analysisRequestRepository.findById(anyString()))
          .thenReturn(Mono.just(AnalysisRequestEntity.builder().analysisId("a").analysisStatus("PROCESSING").build()));
      when(videoMetadataRepository.findById(anyString())).thenReturn(Mono.empty());
      com.bulkaibcd.model.VideoMetadataEntity savedPlaceholder =
          com.bulkaibcd.model.VideoMetadataEntity.builder().id("saved").build();
      when(videoMetadataRepository.save(any())).thenReturn(Mono.just(savedPlaceholder));

      // Mock completion check dependencies:
      when(videoInputRepository.findByAnalysisId(anyString()))
          .thenReturn(Flux.just(VideoInputEntity.builder().videoId("v").analysisId("a").build()));

      Mono<ResponseEntity<String>> result = controller.fetchMetadata(request, 0);
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

    StepVerifier.create(controller.fetchMetadata(request, 0))
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
        VideoMetadataEntity.builder().id("A_v1").analysisId("A").videoId("v1").status("COMPLETED").build();
    VideoMetadataEntity done2 =
        VideoMetadataEntity.builder().id("A_v2").analysisId("A").videoId("v2").status("COMPLETED").build();
    VideoMetadataEntity pending =
        VideoMetadataEntity.builder().id("A_v3").analysisId("A").videoId("v3").status("PROCESSING").build();

    when(videoInputRepository.findByAnalysisId("A"))
        .thenReturn(Flux.just(
            VideoInputEntity.builder().videoId("v1").analysisId("A").build(),
            VideoInputEntity.builder().videoId("v2").analysisId("A").build(),
            VideoInputEntity.builder().videoId("v3").analysisId("A").build()
        ));
    when(videoMetadataRepository.findById("A_v1")).thenReturn(Mono.just(done1));
    when(videoMetadataRepository.findById("A_v2")).thenReturn(Mono.just(done2));
    when(videoMetadataRepository.findById("A_v3")).thenReturn(Mono.just(pending));

    Mono<Void> result = invokeCheckCompletion(controller, "A");

    StepVerifier.create(result).verifyComplete();
    verify(analysisRequestRepository, never()).findById(anyString());
    verify(analysisRequestRepository, never()).save(any());
  }

  @Test
  void threeOfThreeVideosDonePromotesParentToCompleted() throws Exception {
    VideoMetadataEntity d1 =
        VideoMetadataEntity.builder().id("A_v1").analysisId("A").videoId("v1").status("COMPLETED").build();
    VideoMetadataEntity d2 =
        VideoMetadataEntity.builder().id("A_v2").analysisId("A").videoId("v2").status("COMPLETED").build();
    VideoMetadataEntity d3 =
        VideoMetadataEntity.builder().id("A_v3").analysisId("A").videoId("v3").status("COMPLETED").build();
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("A").analysisStatus("PROCESSING").build();

    when(videoInputRepository.findByAnalysisId("A"))
        .thenReturn(Flux.just(
            VideoInputEntity.builder().videoId("v1").analysisId("A").build(),
            VideoInputEntity.builder().videoId("v2").analysisId("A").build(),
            VideoInputEntity.builder().videoId("v3").analysisId("A").build()
        ));
    when(videoMetadataRepository.findById("A_v1")).thenReturn(Mono.just(d1));
    when(videoMetadataRepository.findById("A_v2")).thenReturn(Mono.just(d2));
    when(videoMetadataRepository.findById("A_v3")).thenReturn(Mono.just(d3));
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
    when(videoInputRepository.findByAnalysisId("E")).thenReturn(Flux.empty());

    StepVerifier.create(invokeCheckCompletion(controller, "E")).verifyComplete();
    verify(analysisRequestRepository, never()).save(any());
  }

  // --- C: Transient vs. Terminal failure logs persistence -----------------

  @Test
  void fetchMetadataTransientFailureDoesNotWriteErrorMessage() throws Exception {
    TaskRequest request = new TaskRequest();
    request.setPromptType("A_ATTRACT");
    request.setAnalysisId("a");
    request.setVideoId("v");
    request.setVideoUri("gs://bucket/video.mp4");

    when(geminiService.callGemini(anyString(), anyString(), any()))
        .thenThrow(new RuntimeException("Rate limit 429"));
    when(analysisRequestRepository.findById("a"))
        .thenReturn(Mono.just(AnalysisRequestEntity.builder().analysisId("a").analysisStatus("PROCESSING").build()));

    VideoMetadataEntity metadataPlaceholder = VideoMetadataEntity.builder()
        .id("a_v")
        .analysisId("a")
        .videoId("v")
        .status("PROCESSING")
        .build();
    when(videoMetadataRepository.findById("a_v")).thenReturn(Mono.just(metadataPlaceholder));

    ArgumentCaptor<VideoMetadataEntity> captor = ArgumentCaptor.forClass(VideoMetadataEntity.class);
    when(videoMetadataRepository.save(captor.capture()))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // executionCount = 0 (first attempt, transient)
    Mono<ResponseEntity<String>> result = controller.fetchMetadata(request, 0);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode().value()).isEqualTo(500);
          assertThat(resp.getBody()).contains("retrying: Rate limit 429");
        })
        .verifyComplete();

    // Verify the document saved has NO error message!
    assertThat(captor.getValue().getErrorMessage()).isNull();
  }

  @Test
  void fetchMetadataTerminalFailureWritesErrorMessage() throws Exception {
    TaskRequest request = new TaskRequest();
    request.setPromptType("A_ATTRACT");
    request.setAnalysisId("a");
    request.setVideoId("v");
    request.setVideoUri("gs://bucket/video.mp4");

    when(geminiService.callGemini(anyString(), anyString(), any()))
        .thenThrow(new RuntimeException("Rate limit 429"));
    when(analysisRequestRepository.findById("a"))
        .thenReturn(Mono.just(AnalysisRequestEntity.builder().analysisId("a").analysisStatus("PROCESSING").build()));
    when(analysisRequestRepository.save(any()))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    VideoMetadataEntity metadataPlaceholder = VideoMetadataEntity.builder()
        .id("a_v")
        .analysisId("a")
        .videoId("v")
        .status("PROCESSING")
        .build();
    when(videoMetadataRepository.findById("a_v")).thenReturn(Mono.just(metadataPlaceholder));

    // completion gate checking dependencies
    when(videoInputRepository.findByAnalysisId("a"))
        .thenReturn(Flux.just(VideoInputEntity.builder().videoId("v").analysisId("a").build()));

    ArgumentCaptor<VideoMetadataEntity> captor = ArgumentCaptor.forClass(VideoMetadataEntity.class);
    when(videoMetadataRepository.save(captor.capture()))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // executionCount = 2 (third attempt, terminal retry limit reached)
    Mono<ResponseEntity<String>> result = controller.fetchMetadata(request, 2);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
          assertThat(resp.getBody()).contains("Recorded terminal failure");
        })
        .verifyComplete();

    // Verify terminal failure logs the error
    assertThat(captor.getValue().getErrorMessage()).isEqualTo("Rate limit 429");
    assertThat(captor.getValue().getAScore()).isEqualTo(0); // fallback score 0
  }

  @Test
  void extractRawMetadataTransientFailureDoesNotWriteErrorMessage() throws Exception {
    TaskRequest request = new TaskRequest();
    request.setPromptType("BRAND");
    request.setAnalysisId("a");
    request.setVideoId("v");
    request.setVideoUri("gs://bucket/video.mp4");

    when(geminiService.callGemini(anyString(), anyString(), any()))
        .thenThrow(new RuntimeException("Rate limit 429"));
    when(analysisRequestRepository.findById("a"))
        .thenReturn(Mono.just(AnalysisRequestEntity.builder().analysisId("a").analysisStatus("PROCESSING").build()));

    VideoMetadataEntity metadataPlaceholder = VideoMetadataEntity.builder()
        .id("a_v")
        .analysisId("a")
        .videoId("v")
        .status("PROCESSING")
        .build();
    when(videoMetadataRepository.findById("a_v")).thenReturn(Mono.just(metadataPlaceholder));

    ArgumentCaptor<VideoMetadataEntity> captor = ArgumentCaptor.forClass(VideoMetadataEntity.class);
    when(videoMetadataRepository.save(captor.capture()))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // executionCount = 0 (first attempt, transient)
    Mono<ResponseEntity<String>> result = controller.extractRawMetadata(request, 0);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode().value()).isEqualTo(500);
          assertThat(resp.getBody()).contains("retrying: Rate limit 429");
        })
        .verifyComplete();

    // Verify the document saved has NO error message!
    assertThat(captor.getValue().getErrorMessage()).isNull();
  }

  @Test
  void extractRawMetadataTerminalFailureWritesErrorMessage() throws Exception {
    TaskRequest request = new TaskRequest();
    request.setPromptType("BRAND");
    request.setAnalysisId("a");
    request.setVideoId("v");
    request.setVideoUri("gs://bucket/video.mp4");

    when(geminiService.callGemini(anyString(), anyString(), any()))
        .thenThrow(new RuntimeException("Rate limit 429"));
    when(analysisRequestRepository.findById("a"))
        .thenReturn(Mono.just(AnalysisRequestEntity.builder().analysisId("a").analysisStatus("PROCESSING").build()));

    VideoMetadataEntity metadataPlaceholder = VideoMetadataEntity.builder()
        .id("a_v")
        .analysisId("a")
        .videoId("v")
        .status("PROCESSING")
        .build();
    when(videoMetadataRepository.findById("a_v")).thenReturn(Mono.just(metadataPlaceholder));

    ArgumentCaptor<VideoMetadataEntity> captor = ArgumentCaptor.forClass(VideoMetadataEntity.class);
    when(videoMetadataRepository.save(captor.capture()))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // executionCount = 2 (third attempt, terminal)
    Mono<ResponseEntity<String>> result = controller.extractRawMetadata(request, 2);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
          assertThat(resp.getBody()).contains("Recorded terminal extraction failure");
        })
        .verifyComplete();

    // Verify terminal failure logs the error
    assertThat(captor.getValue().getErrorMessage()).isEqualTo("Rate limit 429");
    assertThat(captor.getValue().getBrand()).isEqualTo(""); // fallback blank brand
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
