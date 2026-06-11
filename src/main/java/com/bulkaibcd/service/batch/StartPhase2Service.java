package com.bulkaibcd.service.batch;

import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.service.analysis.ApiService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service responsible for dispatching Phase 2 consolidated feature analysis (Vertex AI Batch
 * Prediction).
 *
 * <p>Handles idempotency gate checks, filtering valid video inputs, submitting the Vertex AI batch
 * job, updating Firestore/Database statuses to BATCH_QUEUED, and enqueuing the async poller task.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StartPhase2Service implements ApiService<Map<String, String>, ResponseEntity<String>> {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoInputRepository videoInputRepository;
  private final VideoMetadataRepository videoMetadataRepository;
  private final BatchPredictionOrchestrator batchPredictionOrchestrator;
  private final Firestore firestore;
  private final CloudTasksQueueClient cloudTasksQueueClient;

  @Override
  public Mono<ResponseEntity<String>> execute(Map<String, String> payload) {
    String analysisId = payload == null ? null : payload.get("analysisId");
    if (analysisId == null || analysisId.isBlank()) {
      return Mono.just(ResponseEntity.badRequest().body("analysisId is required"));
    }

    log.info(
        "StartPhase2Service: Starting Phase 2 consolidated feature analysis dispatcher for run: {}",
        analysisId);

    return analysisRequestRepository
        .findById(analysisId)
        .flatMap(
            parent -> {
              if (AnalysisStatus.CANCELLED.name().equals(parent.getAnalysisStatus())
                  || AnalysisStatus.DELETED.name().equals(parent.getAnalysisStatus())) {
                log.info(
                    "StartPhase2Service: Skipping Phase 2 start — parent status is {}",
                    parent.getAnalysisStatus());
                return Mono.just(
                    ResponseEntity.ok("Skipped: parent " + parent.getAnalysisStatus()));
              }

              if (AnalysisStatus.BATCH_QUEUED.name().equals(parent.getAnalysisStatus())
                  || AnalysisStatus.COMPLETED.name().equals(parent.getAnalysisStatus())) {
                log.info(
                    "StartPhase2Service: Batch prediction already queued or completed for analysis:"
                        + " {}. Skipping duplicate trigger.",
                    analysisId);
                return Mono.just(ResponseEntity.ok("Already triggered"));
              }

              return videoInputRepository
                  .findByAnalysisId(analysisId)
                  .collectList()
                  .flatMap(
                      inputs -> {
                        List<String> validVideoIds =
                            inputs.stream()
                                .filter(
                                    v ->
                                        v.getErrorMessage() == null
                                            || v.getErrorMessage().isEmpty())
                                .map(VideoInputEntity::getVideoId)
                                .collect(Collectors.toList());

                        if (validVideoIds.isEmpty()) {
                          log.warn(
                              "StartPhase2Service: No valid videos to audit for batch run: {}",
                              analysisId);
                          parent.setAnalysisStatus(AnalysisStatus.COMPLETED.name());
                          parent.setUpdatedAt(Timestamp.now());
                          return analysisRequestRepository
                              .save(parent)
                              .thenReturn(ResponseEntity.ok("No valid videos to analyze"));
                        }

                        return Flux.fromIterable(validVideoIds)
                            .flatMap(
                                videoId ->
                                    videoMetadataRepository
                                        .findById(analysisId + "_" + videoId)
                                        .defaultIfEmpty(
                                            VideoMetadataEntity.builder()
                                                .status(AnalysisStatus.PROCESSING.name())
                                                .build()))
                            .collectList()
                            .flatMap(
                                metadataList -> {
                                  try {
                                    String batchJobId =
                                        batchPredictionOrchestrator.submitPhase2Job(
                                            analysisId,
                                            metadataList,
                                            parent.getAnalysisType(),
                                            parent.getBrandName());

                                    for (VideoMetadataEntity v : metadataList) {
                                      v.setStatus(AnalysisStatus.BATCH_QUEUED.name());
                                    }

                                    parent.setAnalysisStatus(AnalysisStatus.BATCH_QUEUED.name());
                                    parent.setUpdatedAt(Timestamp.now());

                                    Mono<Void> updateDb =
                                        Flux.fromIterable(metadataList)
                                            .flatMap(
                                                v ->
                                                    Mono.fromCallable(
                                                        () ->
                                                            firestore
                                                                .collection("video_metadata")
                                                                .document(v.getId())
                                                                .set(v)
                                                                .get()))
                                            .subscribeOn(
                                                reactor.core.scheduler.Schedulers.boundedElastic())
                                            .then(analysisRequestRepository.save(parent).then());

                                    String pollerPayload =
                                        String.format(
                                            "{\"analysisId\":\"%s\",\"batchJobId\":\"%s\",\"attemptCount\":1}",
                                            analysisId, batchJobId);
                                    String pollerSuffix =
                                        String.format("%s_P2_POLL_attempt_1", analysisId);

                                    return updateDb
                                        .then(
                                            Mono.fromCallable(
                                                () -> {
                                                  cloudTasksQueueClient.enqueueTask(
                                                      "/api/v2/worker/check-phase2-status",
                                                      pollerPayload,
                                                      pollerSuffix,
                                                      300);
                                                  return ResponseEntity.ok(
                                                      "Phase 2 batch successfully launched");
                                                }))
                                        .subscribeOn(
                                            reactor.core.scheduler.Schedulers.boundedElastic());
                                  } catch (Exception e) {
                                    log.error(
                                        "StartPhase2Service: Failed to trigger Phase 2 batch"
                                            + " prediction",
                                        e);
                                    return Mono.just(
                                        ResponseEntity.internalServerError().body(e.getMessage()));
                                  }
                                });
                      });
            });
  }
}
