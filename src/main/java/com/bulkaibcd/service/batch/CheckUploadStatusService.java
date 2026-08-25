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

package com.bulkaibcd.service.batch;

import com.bulkaibcd.client.BoqInputServiceClient;
import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.mapper.EntityMapper;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.UploadStatusResponse;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.model.VideoUploadStatusInfo;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.analysis.ApiService;
import com.bulkaibcd.service.youtube.YouTubeResolveService;
import com.google.cloud.Timestamp;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A worker service responsible for polling Boq unlisted video batch upload status and triggering Phase 1 analysis upon completion.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CheckUploadStatusService
    implements ApiService<Map<String, Object>, ResponseEntity<String>> {

  private static final int MAX_ATTEMPTS = 60;
  private static final int POLL_INTERVAL_SECONDS = 20;

  private final BoqInputServiceClient boqInputServiceClient;
  private final VideoInputRepository videoInputRepository;
  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoMetadataRepository videoMetadataRepository;
  private final BatchPredictionOrchestrator batchPredictionOrchestrator;
  private final CloudTasksQueueClient cloudTasksQueueClient;

  /**
   * Executes the upload status polling check for an ongoing Boq batch upload.
   *
   * @param payload map containing analysisId, requestId, and attemptCount
   * @return a reactive {@link Mono} indicating polling or transition status
   */
  @Override
  public Mono<ResponseEntity<String>> execute(Map<String, Object> payload) {
    String analysisId = (String) payload.get("analysisId");
    String requestId = (String) payload.get("requestId");
    int attemptCount = payload.containsKey("attemptCount")
        ? Integer.parseInt(payload.get("attemptCount").toString())
        : 1;

    log.info(
        "CheckUploadStatusService: Checking Boq upload status for analysisId: {}, requestId: {}, attempt: {}/{}",
        analysisId,
        requestId,
        attemptCount,
        MAX_ATTEMPTS);

    return Mono.fromCallable(() -> boqInputServiceClient.getUploadStatus(requestId))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(statusResponse -> handleStatusResponse(analysisId, requestId, attemptCount, statusResponse))
        .onErrorResume(
            e -> {
              log.error(
                  "CheckUploadStatusService: Error while checking upload status for analysisId: {}, requestId: {}",
                  analysisId,
                  requestId,
                  e);
              return Mono.just(
                  ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                      .body("Status check failed: " + e.getMessage()));
            });
  }

  private Mono<ResponseEntity<String>> handleStatusResponse(
      String analysisId,
      String requestId,
      int attemptCount,
      UploadStatusResponse statusResponse) {
    log.info(
        "CheckUploadStatusService: Status for analysisId: {}, requestId: {} -> Total: {}, Completed: {}, InProgress: {}, Failed: {}, AllCompleted: {}",
        analysisId,
        requestId,
        statusResponse.getTotalCount(),
        statusResponse.getCompletedCount(),
        statusResponse.getInProgressCount(),
        statusResponse.getFailedCount(),
        statusResponse.isAllCompleted());

    if (!statusResponse.isAllCompleted()) {
      if (attemptCount >= MAX_ATTEMPTS) {
        log.error(
            "CheckUploadStatusService: Upload polling timed out after {} attempts for analysisId: {}, requestId: {}",
            attemptCount,
            analysisId,
            requestId);
        return markAnalysisFailed(analysisId, "Boq video upload timed out after maximum polling attempts")
            .thenReturn(
                ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT)
                    .body("Boq video upload timed out"));
      }

      int nextAttempt = attemptCount + 1;
      String nextPayload =
          String.format(
              "{\"analysisId\":\"%s\",\"requestId\":\"%s\",\"attemptCount\":%d}",
              analysisId, requestId, nextAttempt);
      String pollerSuffix =
          String.format("%s_UPLOAD_POLL_attempt_%d", analysisId, nextAttempt);

      try {
        cloudTasksQueueClient.enqueueTask(
            "/api/v2/worker/check-upload-status",
            nextPayload,
            pollerSuffix,
            POLL_INTERVAL_SECONDS);
        log.info(
            "CheckUploadStatusService: Upload still in progress for analysisId: {}. Enqueued poll attempt {} in {}s",
            analysisId,
            nextAttempt,
            POLL_INTERVAL_SECONDS);
        return Mono.just(ResponseEntity.ok("Upload in progress, next poll enqueued"));
      } catch (IOException e) {
        log.error("CheckUploadStatusService: Failed to enqueue next upload poll task", e);
        return Mono.error(e);
      }
    }

    log.info(
        "CheckUploadStatusService: All uploads completed for analysisId: {}, requestId: {}. Updating video records with GCS URIs...",
        analysisId,
        requestId);

    return videoInputRepository
        .findByAnalysisId(analysisId)
        .collectList()
        .flatMap(videos -> updateVideosWithUploadResults(videos, statusResponse))
        .flatMap(updatedVideos -> launchPhase1Analysis(analysisId, updatedVideos));
  }

  private Mono<List<VideoInputEntity>> updateVideosWithUploadResults(
      List<VideoInputEntity> videos, UploadStatusResponse statusResponse) {
    List<VideoUploadStatusInfo> statusInfos =
        statusResponse.getVideoUploadStatuses() != null
            ? statusResponse.getVideoUploadStatuses()
            : List.of();

    return Flux.fromIterable(videos)
        .flatMap(
            video -> {
              if (!Boolean.TRUE.equals(video.getUnlisted())) {
                return Mono.just(video);
              }

              String ytId = YouTubeResolveService.extractVideoId(video.getVideoUrl());
              VideoUploadStatusInfo matchingInfo =
                  statusInfos.stream()
                      .filter(
                          info ->
                              (ytId != null && ytId.equalsIgnoreCase(info.getVideoId()))
                                  || (video.getVideoId() != null
                                      && video.getVideoId().equalsIgnoreCase(info.getVideoId())))
                      .findFirst()
                      .orElse(null);

              if (matchingInfo != null) {
                if (matchingInfo.getGcsPath() != null && !matchingInfo.getGcsPath().isBlank()) {
                  String gcsPath = matchingInfo.getGcsPath().replaceFirst("^gs://", "");
                  video.setGcsObjectId(gcsPath);
                  log.info(
                      "CheckUploadStatusService: Video {} mapped to GCS object: {}",
                      video.getVideoId(),
                      gcsPath);
                } else if (matchingInfo.getErrorMessage() != null
                    && !matchingInfo.getErrorMessage().isBlank()) {
                  video.setErrorMessage("Boq upload failed: " + matchingInfo.getErrorMessage());
                  log.warn(
                      "CheckUploadStatusService: Video {} failed in Boq: {}",
                      video.getVideoId(),
                      matchingInfo.getErrorMessage());
                } else if ("UPLOAD_FAILED".equalsIgnoreCase(matchingInfo.getStatus())) {
                  video.setErrorMessage("Boq upload failed for video");
                  log.warn(
                      "CheckUploadStatusService: Video {} marked as UPLOAD_FAILED in Boq",
                      video.getVideoId());
                }
              }

              return videoInputRepository.save(video);
            })
        .collectList();
  }

  private Mono<ResponseEntity<String>> launchPhase1Analysis(
      String analysisId, List<VideoInputEntity> videos) {
    log.info(
        "CheckUploadStatusService: Seeding metadata and launching Phase 1 for analysisId: {} with {} video(s)",
        analysisId,
        videos.size());
    return seedMetadata(videos)
        .then(
            Mono.fromRunnable(
                () -> {
                  try {
                    String batchJobId =
                        batchPredictionOrchestrator.submitPhase1Job(analysisId, videos);
                    if ("NO_VALID_VIDEOS".equals(batchJobId)) {
                      log.info(
                          "CheckUploadStatusService: No valid videos for Phase 1. Stopping pipeline.");
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
                        "CheckUploadStatusService: Phase 1 Consolidated Batch Job successfully launched (Job ID: {}). Poller enqueued.",
                        batchJobId);
                  } catch (Exception e) {
                    log.error(
                        "CheckUploadStatusService: Phase 1 Consolidated Batch Job creation failed!",
                        e);
                    throw new RuntimeException(e);
                  }
                }))
        .thenReturn(ResponseEntity.ok("Boq upload completed and Phase 1 batch launched"));
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

  private Mono<Void> markAnalysisFailed(String analysisId, String errorMessage) {
    return analysisRequestRepository
        .findById(analysisId)
        .flatMap(
            parent -> {
              parent.setAnalysisStatus(AnalysisStatus.FAILED.name());
              parent.setUpdatedAt(Timestamp.now());
              return analysisRequestRepository.save(parent);
            })
        .then();
  }
}
