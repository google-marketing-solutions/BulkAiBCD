package com.bulkaibcd.service.batch;

import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.PollRequest;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.client.VertexBatchClient;
import com.bulkaibcd.service.analysis.ApiService;
import com.google.cloud.aiplatform.v1.BatchPredictionJob;
import com.google.cloud.aiplatform.v1.JobState;
import com.google.rpc.Status;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service responsible for polling the status of Phase 2 Vertex AI Batch Prediction jobs.
 *
 * <p>Handles parent cancellation checks, querying VertexBatchJobService, calculating exponential
 * backoff delays for re-polling, and enqueuing the Phase 2 results processor webhook upon
 * completion.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CheckPhase2StatusService implements ApiService<PollRequest, ResponseEntity<String>> {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final CloudTasksQueueClient cloudTasksQueueClient;
  private final VertexBatchClient vertexBatchClient;

  @Override
  public Mono<ResponseEntity<String>> execute(PollRequest request) {
    log.info(
        "CheckPhase2StatusService: Polling check-phase2-status for analysisId: {}, batchJobId: {},"
            + " attempt: {}",
        request.getAnalysisId(),
        request.getBatchJobId(),
        request.getAttemptCount());

    return analysisRequestRepository
        .findById(request.getAnalysisId())
        .map(AnalysisRequestEntity::getAnalysisStatus)
        .defaultIfEmpty(AnalysisStatus.DELETED.name())
        .flatMap(
            status -> {
              if (AnalysisStatus.CANCELLED.name().equals(status)
                  || AnalysisStatus.DELETED.name().equals(status)) {
                log.info(
                    "CheckPhase2StatusService: Skipping batch polling check — parent status is {}",
                    status);
                return Mono.just(ResponseEntity.ok("Skipped: parent " + status));
              }
              return runCheckPhase2Status(request);
            });
  }

  private Mono<ResponseEntity<String>> runCheckPhase2Status(PollRequest request) {
    String analysisId = request.getAnalysisId();
    String batchJobId = request.getBatchJobId();
    int attempts = request.getAttemptCount();

    return Mono.fromCallable(
            () -> {
              BatchPredictionJob job = vertexBatchClient.getBatchPredictionJob(batchJobId);
              JobState state = job.getState();
              Status error = job.getError();

              log.info(
                  "CheckPhase2StatusService: Phase 2 Batch Job {} for analysisId {} is in state:"
                      + " {}",
                  batchJobId,
                  analysisId,
                  state);

              if (state == JobState.JOB_STATE_PENDING
                  || state == JobState.JOB_STATE_QUEUED
                  || state == JobState.JOB_STATE_RUNNING) {

                int nextAttempt = attempts + 1;
                int delay = getBackoffDelay(nextAttempt);

                String nextPayload =
                    String.format(
                        "{\"analysisId\":\"%s\",\"batchJobId\":\"%s\",\"attemptCount\":%d}",
                        analysisId, batchJobId, nextAttempt);
                String nextSuffix = String.format("%s_P2_POLL_attempt_%d", analysisId, nextAttempt);

                log.info(
                    "CheckPhase2StatusService: Phase 2 Job still in-flight. Scheduling next check"
                        + " in {}s. Suffix: {}",
                    delay,
                    nextSuffix);
                try {
                  cloudTasksQueueClient.enqueueTask(
                      "/api/v2/worker/check-phase2-status", nextPayload, nextSuffix, delay);
                } catch (IOException e) {
                  log.error(
                      "CheckPhase2StatusService: Failed to re-enqueue Phase 2 status check", e);
                  throw new RuntimeException(e);
                }
                return "RUNNING";

              } else if (state == JobState.JOB_STATE_SUCCEEDED) {
                String outputGcsUri = job.getOutputInfo().getGcsOutputDirectory();
                log.info(
                    "CheckPhase2StatusService: Phase 2 Batch Job Succeeded! Stage output staged at:"
                        + " {}. Enqueuing results parser.",
                    outputGcsUri);

                String processPayload =
                    String.format(
                        "{\"analysisId\":\"%s\",\"gcsUri\":\"%s\"}", analysisId, outputGcsUri);
                String processSuffix = String.format("%s_P2_PROCESS_RESULTS", analysisId);

                try {
                  cloudTasksQueueClient.enqueueTask(
                      "/api/v2/worker/process-phase2-results", processPayload, processSuffix, null);
                } catch (IOException e) {
                  log.error(
                      "CheckPhase2StatusService: Failed to enqueue Phase 2 results processing", e);
                  throw new RuntimeException(e);
                }
                return "SUCCEEDED";

              } else {
                String errorMsg = error != null ? error.getMessage() : "Unknown Vertex error";
                log.error(
                    "CheckPhase2StatusService: Vertex Phase 2 Batch job {} failed! State: {},"
                        + " Error: {}",
                    batchJobId,
                    state,
                    errorMsg);

                String fallbackPayload =
                    String.format(
                        "{\"analysisId\":\"%s\",\"gcsUri\":\"FAILED_%s\"}",
                        analysisId, errorMsg.replaceAll("[^A-Za-z0-9]", "_"));
                String fallbackSuffix = String.format("%s_P2_PROCESS_RESULTS_FAIL", analysisId);

                try {
                  cloudTasksQueueClient.enqueueTask(
                      "/api/v2/worker/process-phase2-results",
                      fallbackPayload,
                      fallbackSuffix,
                      null);
                } catch (IOException e) {
                  log.error(
                      "CheckPhase2StatusService: Failed to enqueue Phase 2 terminal fallback"
                          + " results processing",
                      e);
                  throw new RuntimeException(e);
                }
                return "FAILED";
              }
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .flatMap(status -> Mono.just(ResponseEntity.ok("Phase 2 status check handled: " + status)))
        .onErrorResume(
            e -> {
              log.error(
                  "CheckPhase2StatusService: Critical error polling Phase 2 status checker!", e);
              return Mono.just(
                  ResponseEntity.internalServerError()
                      .body("Phase 2 Polling retry forced: " + e.getMessage()));
            });
  }

  private int getBackoffDelay(int attempt) {
    if (attempt <= 4) return 600;
    return 180;
  }
}
