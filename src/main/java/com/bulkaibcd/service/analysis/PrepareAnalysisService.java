/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bulkaibcd.service.analysis;

// BEGIN-INTERNAL
import com.bulkaibcd.client.BoqInputServiceClient;
// END-INTERNAL
import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.client.GoogleDriveClient;
import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.enums.SourceType;
import com.bulkaibcd.mapper.EntityMapper;
import com.bulkaibcd.model.AnalysisRequestEntity;
// BEGIN-INTERNAL
import com.bulkaibcd.model.UploadUnlistedVideosRequest;
import com.bulkaibcd.model.UploadUnlistedVideosResponse;
// END-INTERNAL
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.batch.BatchPredictionOrchestrator;
import com.bulkaibcd.service.youtube.YouTubeResolveService;
import com.google.cloud.Timestamp;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A service responsible for orchestrating the preparation of video analyses, including Drive and unlisted YouTube video ingestion.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PrepareAnalysisService
    implements ApiService<Map<String, String>, ResponseEntity<String>> {

  /**
   * Placeholder requester written by clients before identity was captured server-side. Analyses
   * still carrying it cannot be attributed and must not reach the Boq backend.
   */
  private static final String LEGACY_REQUESTER_ID = "default-user";

  private final VideoInputRepository videoInputRepository;
  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoMetadataRepository videoMetadataRepository;
  private final BatchPredictionOrchestrator batchPredictionOrchestrator;
  private final CloudTasksQueueClient cloudTasksQueueClient;
  private final ObjectProvider<GoogleDriveClient> driveIngestServiceProvider;

  // BEGIN-INTERNAL
  @Autowired(required = false)
  private BoqInputServiceClient boqInputServiceClient;
  // END-INTERNAL

  @Value("${app.uploads-bucket}")
  private String uploadsBucket;


  /**
   * Executes the preparation workflow for an analysis job.
   *
   * @param payload map containing the analysisId to prepare
   * @return a reactive {@link Mono} emitting the HTTP response indicating preparation status
   */
  @Override
  public Mono<ResponseEntity<String>> execute(Map<String, String> payload) {
    String analysisId = payload.get("analysisId");
    log.info("PrepareAnalysisService: Starting preparation pipeline for analysisId: {}", analysisId);

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
              List<VideoInputEntity> unlistedVideos = new ArrayList<>();
              List<VideoInputEntity> publicVideos = new ArrayList<>();
              for (VideoInputEntity v : ingested) {
                if (SourceType.YOUTUBE.name().equalsIgnoreCase(v.getSourceType())
                    && Boolean.TRUE.equals(v.getUnlisted())) {
                  unlistedVideos.add(v);
                } else {
                  publicVideos.add(v);
                }
              }

              log.info(
                  "PrepareAnalysisService: Analysis {}: Total videos: {}, Unlisted YouTube: {}, Standard/Drive: {}",
                  analysisId,
                  ingested.size(),
                  unlistedVideos.size(),
                  publicVideos.size());

              if (!unlistedVideos.isEmpty()) {
                // BEGIN-INTERNAL
                if (boqInputServiceClient != null) {
                  log.info(
                      "PrepareAnalysisService: Analysis {}: Routing {} unlisted video(s) to Boq InputService.",
                      analysisId,
                      unlistedVideos.size());
                  return initiateUnlistedVideosUpload(analysisId, unlistedVideos);
                }
                // END-INTERNAL
                return Mono.just(
                    ResponseEntity.badRequest().body("Unlisted YouTube videos are not supported."));
              }

              return seedMetadata(ingested)
                  .then(
                      Mono.fromRunnable(
                          () -> {
                            try {
                              String batchJobId =
                                  batchPredictionOrchestrator.submitPhase1Job(analysisId, ingested);
                              if ("NO_VALID_VIDEOS".equals(batchJobId)) {
                                log.info(
                                    "PrepareAnalysisService: No valid videos for Phase 1. Stopping pipeline.");
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
                                  300);
                              log.info(
                                  "PrepareAnalysisService: Phase 1 Consolidated Batch Job successfully launched (Job ID: {}). Poller enqueued.",
                                  batchJobId);
                            } catch (Exception e) {
                              log.error(
                                  "PrepareAnalysisService: Phase 1 Consolidated Batch Job creation failed!",
                                  e);
                              throw new RuntimeException(e);
                            }
                          }))
                  .thenReturn(ResponseEntity.ok("Analysis prepared and Phase 1 batch launched"));
            });
  }

  // BEGIN-INTERNAL
  private Mono<ResponseEntity<String>> initiateUnlistedVideosUpload(
      String analysisId, List<VideoInputEntity> unlistedVideos) {
    List<String> unlistedIds = new ArrayList<>();
    for (VideoInputEntity v : unlistedVideos) {
      String ytId = YouTubeResolveService.extractVideoId(v.getVideoUrl());
      if (ytId != null) {
        unlistedIds.add(ytId);
      } else if (v.getVideoId() != null) {
        unlistedIds.add(v.getVideoId());
      }
    }

    String gcsUriPrefix = String.format("gs://%s/unlisted_%s/", uploadsBucket, analysisId);
    log.info(
        "PrepareAnalysisService: Preparing Boq upload request for analysisId: {} with prefix: {} for video IDs: {}",
        analysisId,
        gcsUriPrefix,
        unlistedIds);

    return analysisRequestRepository
        .findById(analysisId)
        .flatMap(
            parent -> {
              String requesterLdap = parent.getRequesterId();
              if (requesterLdap == null
                  || requesterLdap.isBlank()
                  || LEGACY_REQUESTER_ID.equals(requesterLdap)) {
                // The Boq backend attributes its Gin log entry to this value, so an unattributed
                // call would record a prod data access against nobody. Fail the job instead.
                return Mono.error(
                    new IllegalStateException(
                        "Refusing unattributed Boq upload for analysisId: " + analysisId));
              }
              String analysisName = parent.getAnalysisName() != null ? parent.getAnalysisName() : "unlisted_analysis";

              log.info(
                  "PrepareAnalysisService: Attributing Boq upload for analysisId: {} to requester: {}",
                  analysisId,
                  requesterLdap);

              UploadUnlistedVideosRequest request =
                  UploadUnlistedVideosRequest.builder()
                      .requestId(java.util.UUID.randomUUID().toString())
                      .unlistedYoutubeVideoIds(unlistedIds)
                      .gcsUriPrefix(gcsUriPrefix)
                      .userId(requesterLdap)
                      .analysisName(analysisName)
                      .build();


              return Mono.fromCallable(() -> boqInputServiceClient.uploadUnlistedVideosToGcs(request))
                  .subscribeOn(Schedulers.boundedElastic())
                  .flatMap(
                      uploadResponse -> {
                        String requestId = uploadResponse.getRequestId();
                        log.info(
                            "PrepareAnalysisService: UploadUnlistedVideosToGcs successfully initiated for analysis: {}. Assigned Boq requestId: {}",
                            analysisId,
                            requestId);

                        parent.setUploadRequestId(requestId);
                        return analysisRequestRepository
                            .save(parent)
                            .then(
                                Flux.fromIterable(unlistedVideos)
                                    .flatMap(
                                        uv -> {
                                          uv.setUploadRequestId(requestId);
                                          return videoInputRepository.save(uv);
                                        })
                                    .then())
                            .then(
                                Mono.fromRunnable(
                                    () -> {
                                      try {
                                        String pollerPayload =
                                            String.format(
                                                "{\"analysisId\":\"%s\",\"requestId\":\"%s\",\"attemptCount\":1}",
                                                analysisId, requestId);
                                        String pollerSuffix =
                                            String.format("%s_UPLOAD_POLL_attempt_1", analysisId);
                                        cloudTasksQueueClient.enqueueTask(
                                            "/api/v2/worker/check-upload-status",
                                            pollerPayload,
                                            pollerSuffix,
                                            15);
                                        log.info(
                                            "PrepareAnalysisService: Enqueued Cloud Tasks poller for Boq upload status (analysisId: {}, requestId: {})",
                                            analysisId,
                                            requestId);
                                      } catch (IOException e) {
                                        log.error(
                                            "PrepareAnalysisService: Failed to enqueue Boq upload poller task",
                                            e);
                                        throw new RuntimeException(e);
                                      }
                                    }))
                            .thenReturn(
                                ResponseEntity.ok(
                                    "Analysis prepared and Boq unlisted upload launched"));
                      });
            });
  }
  // END-INTERNAL

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
                  .subscribeOn(Schedulers.boundedElastic())
                  .flatMap(
                      gcsObjectId -> {
                        v.setGcsObjectId(gcsObjectId);
                        log.info(
                            "PrepareAnalysisService: Analysis {}: Video {} successfully ingested to GCS: {}",
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
