package com.bulkaibcd.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.client.GoogleDriveClient;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.batch.BatchPredictionOrchestrator;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrepareAnalysisServiceTest {

  private VideoInputRepository videoInputRepo;
  private AnalysisRequestRepository analysisRepo;
  private VideoMetadataRepository videoMetadataRepo;
  private BatchPredictionOrchestrator batchOrchestrator;
  private CloudTasksQueueClient cloudTasksClient;
  private ObjectProvider<GoogleDriveClient> driveProvider;
  private GoogleDriveClient driveClient;
  private PrepareAnalysisService service;

  @BeforeEach
  void setUp() {
    videoInputRepo = mock(VideoInputRepository.class);
    analysisRepo = mock(AnalysisRequestRepository.class);
    videoMetadataRepo = mock(VideoMetadataRepository.class);
    batchOrchestrator = mock(BatchPredictionOrchestrator.class);
    cloudTasksClient = mock(CloudTasksQueueClient.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<GoogleDriveClient> provider = mock(ObjectProvider.class);
    driveProvider = provider;
    driveClient = mock(GoogleDriveClient.class);
    when(driveProvider.getIfAvailable()).thenReturn(driveClient);

    service =
        new PrepareAnalysisService(
            videoInputRepo,
            analysisRepo,
            videoMetadataRepo,
            batchOrchestrator,
            cloudTasksClient,
            driveProvider);
  }

  @Test
  void executeWithEmptyVideosReturnsOkNoVideos() {
    when(videoInputRepo.findByAnalysisId("ana-1")).thenReturn(Flux.empty());

    StepVerifier.create(service.execute(Map.of("analysisId", "ana-1")))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo("No videos to process");
            })
        .verifyComplete();
  }

  @Test
  void executeWithVideosSeedsMetadataAndSubmitsPhase1Job() throws Exception {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PENDING").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));
    when(analysisRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    VideoInputEntity v1 =
        VideoInputEntity.builder()
            .id("ana-1_v1")
            .analysisId("ana-1")
            .videoId("v1")
            .sourceType("YOUTUBE")
            .videoUrl("https://youtube.com/1")
            .build();
    when(videoInputRepo.findByAnalysisId("ana-1")).thenReturn(Flux.just(v1));
    when(videoMetadataRepo.findById("ana-1_v1")).thenReturn(Mono.empty());
    when(videoMetadataRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(batchOrchestrator.submitPhase1Job(anyString(), any())).thenReturn("batch-job-123");

    StepVerifier.create(service.execute(Map.of("analysisId", "ana-1")))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo("Analysis prepared and Phase 1 batch launched");
            })
        .verifyComplete();

    verify(batchOrchestrator).submitPhase1Job("ana-1", java.util.List.of(v1));
    verify(cloudTasksClient)
        .enqueueTask(
            org.mockito.ArgumentMatchers.eq("/api/v2/worker/check-phase1-status"),
            anyString(),
            anyString(),
            anyInt());
  }

  @Test
  void executeWithDriveVideoIngestsToGcs() throws Exception {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PENDING").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));
    when(analysisRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    VideoInputEntity driveVid =
        VideoInputEntity.builder()
            .id("ana-1_v2")
            .analysisId("ana-1")
            .videoId("v2")
            .sourceType("DRIVE")
            .videoUrl("https://drive.google.com/file/d/abc")
            .build();
    when(videoInputRepo.findByAnalysisId("ana-1")).thenReturn(Flux.just(driveVid));
    when(driveClient.ingest("https://drive.google.com/file/d/abc")).thenReturn("drive-gcs-object-id");
    when(videoInputRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(videoMetadataRepo.findById("ana-1_v2")).thenReturn(Mono.empty());
    when(videoMetadataRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(batchOrchestrator.submitPhase1Job(anyString(), any())).thenReturn("batch-job-456");

    StepVerifier.create(service.execute(Map.of("analysisId", "ana-1")))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            })
        .verifyComplete();

    verify(driveClient).ingest("https://drive.google.com/file/d/abc");
    assertThat(driveVid.getGcsObjectId()).isEqualTo("drive-gcs-object-id");
  }
}
