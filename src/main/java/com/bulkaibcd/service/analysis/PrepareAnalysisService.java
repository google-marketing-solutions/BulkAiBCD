package com.bulkaibcd.service.analysis;

import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.enums.SourceType;
import com.bulkaibcd.mapper.EntityMapper;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.client.GoogleDriveClient;
import com.bulkaibcd.service.batch.BatchPredictionOrchestrator;
import com.google.cloud.Timestamp;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service responsible for orchestrating the preparation of video analyses.
 *
 * <p>Handles Drive ingest, metadata seeding, and programmatically launching the Phase 1
 * consolidated Vertex AI Batch Prediction job along with its poller task.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PrepareAnalysisService
    implements ApiService<Map<String, String>, ResponseEntity<String>> {

  private final VideoInputRepository videoInputRepository;
  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoMetadataRepository videoMetadataRepository;
  private final BatchPredictionOrchestrator batchPredictionOrchestrator;
  private final CloudTasksQueueClient cloudTasksQueueClient;
  private final ObjectProvider<GoogleDriveClient> driveIngestServiceProvider;

  /**
   * Executes the preparation workflow.
   *
   * @param payload Map containing the analysisId to prepare
   * @return A reactive {@link Mono} emitting the HTTP response indicating preparation status
   */
  @Override
  public Mono<ResponseEntity<String>> execute(Map<String, String> payload) {
    String analysisId = payload.get("analysisId");
    log.info("PrepareAnalysisService: Starting preparation for analysisId: {}", analysisId);

    return videoInputRepository
        .findByAnalysisId(analysisId)
        .collectList()
        .flatMap(videos -> processVideos(analysisId, videos))
        .onErrorResume(
            e -> {
              log.error("PrepareAnalysisService: Prepare failed for analysis {}", analysisId, e);
              return Mono.just(ResponseEntity.internalServerError().body(e.getMessage()));
            });
  }

  private Mono<ResponseEntity<String>> processVideos(
      String analysisId, List<VideoInputEntity> videos) {
    if (videos.isEmpty()) {
      log.warn("PrepareAnalysisService: No video inputs for analysis {}", analysisId);
      return Mono.just(ResponseEntity.ok("No videos to process"));
    }
    return markParentProcessing(analysisId)
        .then(ingestDriveVideos(videos))
        .flatMap(
            ingested -> {
              log.info(
                  "PrepareAnalysisService: Analysis {}: Ingested {} videos. Proceeding to seed"
                      + " metadata.",
                  analysisId,
                  ingested.size());
              return seedMetadata(ingested)
                  .then(
                      Mono.fromRunnable(
                          () -> {
                            try {
                              String batchJobId =
                                  batchPredictionOrchestrator.submitPhase1Job(analysisId, ingested);
                              if ("NO_VALID_VIDEOS".equals(batchJobId)) {
                                log.info(
                                    "PrepareAnalysisService: No valid videos for Phase 1. Stopping"
                                        + " pipeline.");
                                return;
                              }
                              String pollerPayload =
                                  String.format(
                                      "{\"analysisId\":\"%s\",\"batchJobId\":\"%s\",\"attemptCount\":1}",
                                      analysisId, batchJobId);
                              String pollerSuffix =
                                  String.format("%s_P1_POLL_attempt_1", analysisId);
                              cloudTasksQueueClient.enqueueTask(
                                  "/api/v2/worker/check-phase1-status",
                                  pollerPayload,
                                  pollerSuffix,
                                  300 // 5 minutes warm-up delay!
                                  );
                              log.info(
                                  "PrepareAnalysisService: Phase 1 Consolidated Batch Job"
                                      + " successfully launched. Poller enqueued.");
                            } catch (Exception e) {
                              log.error(
                                  "PrepareAnalysisService: Phase 1 Consolidated Batch Job creation"
                                      + " failed!",
                                  e);
                              throw new RuntimeException(e);
                            }
                          }))
                  .thenReturn(ResponseEntity.ok("Analysis prepared and Phase 1 batch launched"));
            });
  }

  private Mono<List<VideoInputEntity>> ingestDriveVideos(List<VideoInputEntity> videos) {
    return Flux.fromIterable(videos)
        .flatMap(
            v -> {
              if (!SourceType.DRIVE.name().equalsIgnoreCase(v.getSourceType())) return Mono.just(v);
              log.info(
                  "PrepareAnalysisService: Analysis {}: Processing Drive ingest for video {}",
                  v.getAnalysisId(),
                  v.getVideoId());
              if (v.getGcsObjectId() != null && !v.getGcsObjectId().isEmpty()) {
                return Mono.just(v);
              }
              GoogleDriveClient svc = driveIngestServiceProvider.getIfAvailable();
              if (svc == null) {
                log.warn(
                    "PrepareAnalysisService: Drive ingest requested but service unavailable for {}",
                    v.getId());
                return Mono.just(v);
              }
              return Mono.fromCallable(() -> svc.ingest(v.getVideoUrl()))
                  .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                  .flatMap(
                      gcsObjectId -> {
                        v.setGcsObjectId(gcsObjectId);
                        log.info(
                            "PrepareAnalysisService: Analysis {}: Video {} successfully ingested to"
                                + " GCS: {}",
                            v.getAnalysisId(),
                            v.getVideoId(),
                            gcsObjectId);
                        return videoInputRepository.save(v);
                      })
                  .onErrorResume(
                      err -> {
                        log.error(
                            "PrepareAnalysisService: Drive ingest failed for {}",
                            v.getVideoUrl(),
                            err);
                        v.setErrorMessage("drive ingest failed: " + err.getMessage());
                        return videoInputRepository.save(v);
                      });
            })
        .collectList();
  }

  private Mono<Void> markParentProcessing(String analysisId) {
    return analysisRequestRepository
        .findById(analysisId)
        .flatMap(
            request -> {
              request.setAnalysisStatus(AnalysisStatus.PROCESSING.name());
              request.setUpdatedAt(Timestamp.now());
              return analysisRequestRepository.save(request);
            })
        .then();
  }

  private Mono<Void> seedMetadata(List<VideoInputEntity> videos) {
    return Flux.fromIterable(videos)
        .flatMap(
            videoInput -> {
              String docId = videoInput.getAnalysisId() + "_" + videoInput.getVideoId();
              boolean hasError =
                  videoInput.getErrorMessage() != null && !videoInput.getErrorMessage().isEmpty();
              VideoMetadataEntity seed = EntityMapper.toVideoMetadataEntity(videoInput, hasError);
              return videoMetadataRepository
                  .findById(docId)
                  .switchIfEmpty(videoMetadataRepository.save(seed));
            })
        .then();
  }
}
