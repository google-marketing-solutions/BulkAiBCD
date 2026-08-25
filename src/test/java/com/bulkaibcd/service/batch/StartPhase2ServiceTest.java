package com.bulkaibcd.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.Firestore;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class StartPhase2ServiceTest {

  private AnalysisRequestRepository analysisRepo;
  private VideoInputRepository videoInputRepo;
  private VideoMetadataRepository videoMetadataRepo;
  private BatchPredictionOrchestrator batchOrchestrator;
  private Firestore firestore;
  private CloudTasksQueueClient cloudTasksClient;
  private StartPhase2Service service;

  @BeforeEach
  void setUp() {
    analysisRepo = mock(AnalysisRequestRepository.class);
    videoInputRepo = mock(VideoInputRepository.class);
    videoMetadataRepo = mock(VideoMetadataRepository.class);
    batchOrchestrator = mock(BatchPredictionOrchestrator.class);
    firestore = mock(Firestore.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    cloudTasksClient = mock(CloudTasksQueueClient.class);

    service =
        new StartPhase2Service(
            analysisRepo,
            videoInputRepo,
            videoMetadataRepo,
            batchOrchestrator,
            firestore,
            cloudTasksClient);
  }

  @Test
  void executeValidationFailsWhenAnalysisIdMissing() {
    StepVerifier.create(service.execute(Map.of()))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(resp.getBody()).contains("analysisId is required");
            })
        .verifyComplete();
  }

  @Test
  void executeSkipsWhenParentCancelled() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("CANCELLED").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    StepVerifier.create(service.execute(Map.of("analysisId", "ana-1")))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).contains("Skipped: parent CANCELLED");
            })
        .verifyComplete();
  }

  @Test
  void executeSkipsWhenAlreadyTriggered() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("BATCH_QUEUED").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    StepVerifier.create(service.execute(Map.of("analysisId", "ana-1")))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo("Already triggered");
            })
        .verifyComplete();
  }

  @Test
  void executeWithNoValidVideosMarksCompleted() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PROCESSING").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));
    when(analysisRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    VideoInputEntity errorVideo =
        VideoInputEntity.builder().videoId("v1").errorMessage("corrupted").build();
    when(videoInputRepo.findByAnalysisId("ana-1")).thenReturn(Flux.just(errorVideo));

    StepVerifier.create(service.execute(Map.of("analysisId", "ana-1")))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo("No valid videos to analyze");
            })
        .verifyComplete();

    assertThat(parent.getAnalysisStatus()).isEqualTo(AnalysisStatus.COMPLETED.name());
  }

  @Test
  void executeSubmitsPhase2JobAndEnqueuesCheckTask() throws Exception {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder()
            .analysisId("ana-1")
            .analysisType("standard")
            .brandName("Acme")
            .analysisStatus("PROCESSING")
            .build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));
    when(analysisRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    VideoInputEntity v1 =
        VideoInputEntity.builder().videoId("v1").analysisId("ana-1").build();
    when(videoInputRepo.findByAnalysisId("ana-1")).thenReturn(Flux.just(v1));

    VideoMetadataEntity m1 =
        VideoMetadataEntity.builder().id("ana-1_v1").analysisId("ana-1").videoId("v1").build();
    when(videoMetadataRepo.findById("ana-1_v1")).thenReturn(Mono.just(m1));

    when(batchOrchestrator.submitPhase2Job(eq("ana-1"), anyList(), eq("standard"), eq("Acme")))
        .thenReturn("batch-job-123");

    when(firestore.collection("video_metadata").document("ana-1_v1").set(any()).get())
        .thenReturn(null);

    StepVerifier.create(service.execute(Map.of("analysisId", "ana-1")))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo("Phase 2 batch successfully launched");
            })
        .verifyComplete();

    verify(cloudTasksClient)
        .enqueueTask(eq("/api/v2/worker/check-phase2-status"), anyString(), eq("ana-1_P2_POLL_attempt_1"), eq(300));
  }
}
