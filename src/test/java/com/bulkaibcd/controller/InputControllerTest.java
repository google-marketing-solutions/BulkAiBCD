package com.bulkaibcd.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.SubmitAnalysisRequest;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.service.CloudTasksService;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class InputControllerTest {

  @Mock AnalysisRequestRepository repo;
  @Mock VideoInputRepository videoInputRepo;
  @Mock CloudTasksService cloudTasksService;

  @InjectMocks InputController controller;

  private static SubmitAnalysisRequest sampleRequest() {
    return SubmitAnalysisRequest.builder()
        .requesterId("r")
        .analysisName("n")
        .analysisType("t")
        .brandName("Acme")
        .marketingObjective("core_unknown")
        .videos(List.of(
            SubmitAnalysisRequest.VideoInput.builder()
                .sourceType("youtube")
                .videoName("Sample YT Title")
                .videoUrl("https://youtu.be/abc")
                .build()))
        .build();
  }

  @Test
  void submitSucceedsReturnsAnalysisIdAndLeavesRecordInPlace() throws IOException {
    when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(videoInputRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(controller.submitAnalysis(sampleRequest()))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
              assertThat(resp.getBody()).isNotBlank();
            })
        .verifyComplete();

    verify(cloudTasksService).enqueueTask(anyString(), anyString());
    verify(repo, never()).deleteById(anyString());
    verify(videoInputRepo, atLeastOnce()).save(any(VideoInputEntity.class));
  }

  @Test
  void submitRollsBackRecordsWhenEnqueueFails() throws IOException {
    when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(videoInputRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(repo.deleteById(anyString())).thenReturn(Mono.empty());
    when(videoInputRepo.findByAnalysisId(anyString())).thenReturn(Flux.empty());
    doThrow(new IOException("OIDC failed"))
        .when(cloudTasksService)
        .enqueueTask(anyString(), anyString());

    StepVerifier.create(controller.submitAnalysis(sampleRequest()))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode().value()).isEqualTo(500);
              assertThat(resp.getBody()).contains("Failed to submit analysis");
            })
        .verifyComplete();

    // No orphaned parent record.
    verify(repo).deleteById(anyString());
  }

  @Test
  void submitReturns500EvenIfRollbackDeleteAlsoFails() throws IOException {
    when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(videoInputRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(repo.deleteById(anyString()))
        .thenReturn(Mono.error(new RuntimeException("firestore down")));
    when(videoInputRepo.findByAnalysisId(anyString())).thenReturn(Flux.empty());
    doThrow(new IOException("OIDC failed"))
        .when(cloudTasksService)
        .enqueueTask(anyString(), anyString());

    StepVerifier.create(controller.submitAnalysis(sampleRequest()))
        .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(500))
        .verifyComplete();
  }
}
