package com.bulkaibcd.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.model.SubmitAnalysisRequest;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class SubmitAnalysisServiceTest {

  private AnalysisRequestRepository analysisRepo;
  private VideoInputRepository videoInputRepo;
  private CloudTasksQueueClient cloudTasksClient;
  private SubmitAnalysisService service;

  private boolean unlistedSupported = false;


  @BeforeEach
  void setUp() {
    analysisRepo = mock(AnalysisRequestRepository.class);
    videoInputRepo = mock(VideoInputRepository.class);
    cloudTasksClient = mock(CloudTasksQueueClient.class);
    service = new SubmitAnalysisService(analysisRepo, videoInputRepo, cloudTasksClient);
  }

  @Test
  void submitSucceedsAndEnqueuesPrepareTask() throws Exception {
    when(analysisRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(videoInputRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    SubmitAnalysisRequest req =
        SubmitAnalysisRequest.builder()
            .analysisName("Test")
            .videos(List.of(SubmitAnalysisRequest.VideoInput.builder().videoName("V1").build()))
            .build();

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isNotBlank();
            })
        .verifyComplete();

    verify(cloudTasksClient).enqueueTask(org.mockito.ArgumentMatchers.eq("/api/v2/worker/prepare"), anyString());
  }

  @Test
  void submitRollsBackWhenCloudTasksFails() throws Exception {
    when(analysisRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(videoInputRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    VideoInputEntity v1 = VideoInputEntity.builder().id("ana-1_v1").build();
    when(videoInputRepo.findByAnalysisId(anyString())).thenReturn(Flux.just(v1));
    when(videoInputRepo.deleteById("ana-1_v1")).thenReturn(Mono.empty());
    when(analysisRepo.deleteById(anyString())).thenReturn(Mono.empty());

    doThrow(new IOException("Cloud Tasks failure"))
        .when(cloudTasksClient)
        .enqueueTask(anyString(), anyString());

    SubmitAnalysisRequest req = SubmitAnalysisRequest.builder().analysisName("Test").build();

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
              assertThat(resp.getBody()).isEqualTo("Failed to submit analysis");
            })
        .verifyComplete();

    verify(analysisRepo).deleteById(anyString());
  }

  @Test
  void submitWithUnlistedVideoBehavesAppropriately() throws Exception {
    when(analysisRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(videoInputRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    SubmitAnalysisRequest req =
        SubmitAnalysisRequest.builder()
            .analysisName("Unlisted Test")
            .videos(
                List.of(
                    SubmitAnalysisRequest.VideoInput.builder()
                        .videoName("Unlisted Vid")
                        .unlisted(true)
                        .build()))
            .build();

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              if (unlistedSupported) {
                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(resp.getBody()).isNotBlank();
              } else {
                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(resp.getBody()).isEqualTo("Unlisted YouTube videos are not supported.");
              }
            })
        .verifyComplete();
  }
}
