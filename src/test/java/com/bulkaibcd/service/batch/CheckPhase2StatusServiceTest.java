package com.bulkaibcd.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.client.VertexBatchClient;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.PollRequest;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.google.cloud.aiplatform.v1.BatchPredictionJob;
import com.google.cloud.aiplatform.v1.JobState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CheckPhase2StatusServiceTest {

  private AnalysisRequestRepository analysisRepo;
  private CloudTasksQueueClient cloudTasksClient;
  private VertexBatchClient vertexBatchClient;
  private CheckPhase2StatusService service;

  @BeforeEach
  void setUp() {
    analysisRepo = mock(AnalysisRequestRepository.class);
    cloudTasksClient = mock(CloudTasksQueueClient.class);
    vertexBatchClient = mock(VertexBatchClient.class);
    service = new CheckPhase2StatusService(analysisRepo, cloudTasksClient, vertexBatchClient);
  }

  @Test
  void executeSkipsWhenParentCancelled() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("CANCELLED").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    PollRequest req = new PollRequest();
    req.setAnalysisId("ana-1");
    req.setBatchJobId("job-2");

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).contains("Skipped: parent CANCELLED");
            })
        .verifyComplete();
  }

  @Test
  void executeReEnqueuesWhenJobRunning() throws Exception {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PROCESSING").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    BatchPredictionJob job = BatchPredictionJob.newBuilder().setState(JobState.JOB_STATE_RUNNING).build();
    when(vertexBatchClient.getBatchPredictionJob("job-2")).thenReturn(job);

    PollRequest req = new PollRequest();
    req.setAnalysisId("ana-1");
    req.setBatchJobId("job-2");
    req.setAttemptCount(1);

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).contains("RUNNING");
            })
        .verifyComplete();

    verify(cloudTasksClient)
        .enqueueTask(eq("/api/v2/worker/check-phase2-status"), anyString(), eq("ana-1_P2_POLL_attempt_2"), eq(600));
  }

  @Test
  void executeEnqueuesProcessResultsWhenJobSucceeded() throws Exception {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PROCESSING").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    BatchPredictionJob job =
        BatchPredictionJob.newBuilder()
            .setState(JobState.JOB_STATE_SUCCEEDED)
            .setOutputInfo(
                BatchPredictionJob.OutputInfo.newBuilder()
                    .setGcsOutputDirectory("gs://bucket/out2/")
                    .build())
            .build();
    when(vertexBatchClient.getBatchPredictionJob("job-2")).thenReturn(job);

    PollRequest req = new PollRequest();
    req.setAnalysisId("ana-1");
    req.setBatchJobId("job-2");
    req.setAttemptCount(1);

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).contains("SUCCEEDED");
            })
        .verifyComplete();

    verify(cloudTasksClient)
        .enqueueTask(eq("/api/v2/worker/process-phase2-results"), anyString(), eq("ana-1_P2_PROCESS_RESULTS"), any());
  }

  @Test
  void executeEnqueuesFallbackWhenJobFailed() throws Exception {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PROCESSING").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    BatchPredictionJob job =
        BatchPredictionJob.newBuilder()
            .setState(JobState.JOB_STATE_FAILED)
            .setError(com.google.rpc.Status.newBuilder().setMessage("Quota error").build())
            .build();
    when(vertexBatchClient.getBatchPredictionJob("job-2")).thenReturn(job);

    PollRequest req = new PollRequest();
    req.setAnalysisId("ana-1");
    req.setBatchJobId("job-2");
    req.setAttemptCount(1);

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).contains("FAILED");
            })
        .verifyComplete();

    verify(cloudTasksClient)
        .enqueueTask(eq("/api/v2/worker/process-phase2-results"), anyString(), eq("ana-1_P2_PROCESS_RESULTS_FAIL"), any());
  }
}
