package com.bulkaibcd.service.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.client.GeminiClient;
import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.TaskRequest;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ExtractRawMetadataServiceTest {

  private AnalysisRequestRepository analysisRepo;
  private VideoMetadataRepository videoMetadataRepo;
  private VideoInputRepository videoInputRepo;
  private GeminiClient geminiClient;
  private Firestore firestore;
  private CloudTasksQueueClient cloudTasksClient;
  private ExtractRawMetadataService service;

  @BeforeEach
  void setUp() {
    analysisRepo = mock(AnalysisRequestRepository.class);
    videoMetadataRepo = mock(VideoMetadataRepository.class);
    videoInputRepo = mock(VideoInputRepository.class);
    geminiClient = mock(GeminiClient.class);
    firestore = mock(Firestore.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    cloudTasksClient = mock(CloudTasksQueueClient.class);

    service =
        new ExtractRawMetadataService(
            analysisRepo,
            videoMetadataRepo,
            videoInputRepo,
            geminiClient,
            firestore,
            cloudTasksClient);
  }

  @Test
  void unknownPromptTypeReturns400() {
    TaskRequest req = new TaskRequest();
    req.setAnalysisId("ana-1");
    req.setPromptType("INVALID_TYPE");

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(resp.getBody()).isEqualTo("Unknown raw prompt type");
            })
        .verifyComplete();
  }

  @Test
  void parentCancelledSkipsExecution() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("CANCELLED").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    TaskRequest req = new TaskRequest();
    req.setAnalysisId("ana-1");
    req.setPromptType("BRAND");

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).contains("Skipped: parent CANCELLED");
            })
        .verifyComplete();
  }

  @Test
  void alreadyProcessedFieldSkipsGeminiCall() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PROCESSING").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    VideoMetadataEntity metadata =
        VideoMetadataEntity.builder().id("ana-1_v1").brand("Acme").build();
    when(videoMetadataRepo.findById("ana-1_v1")).thenReturn(Mono.just(metadata));

    TaskRequest req = new TaskRequest();
    req.setAnalysisId("ana-1");
    req.setVideoId("v1");
    req.setPromptType("BRAND");

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo("Already processed");
            })
        .verifyComplete();
  }

  @Test
  void transientFailureOnFirstAttemptReturns500ForRetry() throws Exception {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PROCESSING").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    VideoMetadataEntity metadata =
        VideoMetadataEntity.builder().id("ana-1_v1").build();
    when(videoMetadataRepo.findById("ana-1_v1")).thenReturn(Mono.just(metadata));
    when(videoMetadataRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    when(geminiClient.callGemini(any(), any(), any()))
        .thenThrow(new RuntimeException("Rate limit 429"));

    TaskRequest req = new TaskRequest();
    req.setAnalysisId("ana-1");
    req.setVideoId("v1");
    req.setPromptType("BRAND");
    req.setExecutionCount(0);

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
              assertThat(resp.getBody()).contains("retrying: Rate limit 429");
            })
        .verifyComplete();
  }
}
