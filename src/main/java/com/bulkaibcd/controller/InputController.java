package com.bulkaibcd.controller;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.SubmitAnalysisRequest;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.CloudTasksService;
import com.bulkaibcd.service.DriveIngestService;
import com.bulkaibcd.service.UploadUrlService;
import com.google.cloud.Timestamp;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v2/input")
@RequiredArgsConstructor
@Slf4j
public class InputController {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoInputRepository videoInputRepository;
  private final VideoMetadataRepository videoMetadataRepository;
  private final CloudTasksService cloudTasksService;
  private final ObjectProvider<DriveIngestService> driveIngestServiceProvider;
  private final ObjectProvider<UploadUrlService> uploadUrlServiceProvider;

  @PostMapping("/submit")
  public Mono<ResponseEntity<String>> submitAnalysis(@RequestBody SubmitAnalysisRequest request) {
    String analysisId = UUID.randomUUID().toString();
    Timestamp now = Timestamp.now();

    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder()
            .analysisId(analysisId)
            .requesterId(request.getRequesterId())
            .analysisName(request.getAnalysisName())
            .analysisType(request.getAnalysisType())
            .analysisStatus("PENDING")
            .brandName(request.getBrandName())
            .marketingObjective(request.getMarketingObjective())
            .createdAt(now)
            .updatedAt(now)
            .build();

    List<SubmitAnalysisRequest.VideoInput> videos =
        request.getVideos() == null ? List.of() : request.getVideos();

    return analysisRequestRepository
        .save(parent)
        .then(persistVideos(analysisId, videos))
        .then(Mono.defer(() -> enqueuePrepare(analysisId)))
        .onErrorResume(
            err -> rollback(analysisId, err));
  }

  private Mono<Void> persistVideos(String analysisId, List<SubmitAnalysisRequest.VideoInput> videos) {
    return Flux.fromIterable(videos)
        .flatMap(
            videoInput -> {
              String videoId = UUID.randomUUID().toString();
              VideoInputEntity entity =
                  VideoInputEntity.builder()
                      .id(analysisId + "_" + videoId)
                      .analysisId(analysisId)
                      .videoId(videoId)
                      .videoName(videoInput.getVideoName())
                      .videoUrl(videoInput.getVideoUrl())
                      .thumbnailUrl(videoInput.getThumbnailUrl())
                      .sourceType(videoInput.getSourceType())
                      .gcsObjectId(videoInput.getGcsObjectId())
                      .build();
              return videoInputRepository.save(entity);
            })
        .then();
  }

  private Mono<ResponseEntity<String>> enqueuePrepare(String analysisId) {
    try {
      cloudTasksService.enqueueTask(
          "/api/v2/worker/prepare", "{\"analysisId\": \"" + analysisId + "\"}");
      return Mono.just(ResponseEntity.ok(analysisId));
    } catch (IOException e) {
      return Mono.error(e);
    }
  }

  private Mono<ResponseEntity<String>> rollback(String analysisId, Throwable err) {
    log.error("Submit failed for {}; rolling back", analysisId, err);
    return videoInputRepository
        .findByAnalysisId(analysisId)
        .flatMap(videoInput -> videoInputRepository.deleteById(videoInput.getId()))
        .then(
            analysisRequestRepository
                .deleteById(analysisId)
                .onErrorResume(
                    delErr -> {
                      log.error("Rollback delete also failed for {}", analysisId, delErr);
                      return Mono.empty();
                    }))
        .then(
            Mono.just(
                ResponseEntity.internalServerError().body("Failed to submit analysis")));
  }

  /**
   * Expands a Drive URL (file or folder) into a list of video entries the UI
   * can add to the queue. Returns 502 with the Drive API's error message when
   * the file isn't shared with the runtime SA, so the UI can snackbar it
   * instead of silently enqueuing a broken row.
   */
  @PostMapping("/drive-resolve")
  public Mono<ResponseEntity<?>> resolveDrive(@RequestBody Map<String, String> body) {
    String url = body == null ? null : body.get("url");
    if (url == null || url.isBlank()) {
      return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "url is required")));
    }
    DriveIngestService svc = driveIngestServiceProvider.getIfAvailable();
    if (svc == null) {
      return Mono.just(
          ResponseEntity.status(503)
              .body(Map.of("error", "Drive ingest service unavailable")));
    }
    return Mono.fromCallable(() -> svc.resolve(url))
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .<ResponseEntity<?>>map(
            resolved ->
                ResponseEntity.ok(
                    Map.of(
                        "videos",
                        resolved.stream()
                            .map(
                                resolvedFile ->
                                    Map.of(
                                        "videoName", resolvedFile.name() == null ? "" : resolvedFile.name(),
                                        "videoUrl",
                                        resolvedFile.webViewLink() == null ? url : resolvedFile.webViewLink(),
                                        "thumbnailUrl",
                                        resolvedFile.thumbnailLink() == null ? "" : resolvedFile.thumbnailLink()))
                            .toList())))
        .onErrorResume(
            err -> {
              log.warn("Drive resolve failed for {}: {}", url, err.getMessage());
              // Distinguish user-visible failure modes:
              // 400 → URL format we don't know how to parse.
              // 403 → Drive API said access denied (file not shared with SA).
              // 502 → any other upstream Drive API error.
              int status;
              String code;
              if (err instanceof IllegalArgumentException) {
                status = 400;
                code = "UNRECOGNIZED_URL";
              } else if (isDrivePermissionDenied(err)) {
                status = 403;
                code = "ACCESS_DENIED";
              } else {
                status = 502;
                code = "DRIVE_ERROR";
              }
              return Mono.just(
                  ResponseEntity.status(status)
                      .body(Map.of("code", code, "error", err.getMessage() == null ? "Drive error" : err.getMessage())));
            });
  }

  private static boolean isDrivePermissionDenied(Throwable err) {
    // com.google.api.client.googleapis.json.GoogleJsonResponseException wraps
    // the Drive API's HTTP status. 403 (forbidden) is the shared-access case;
    // 404 (not found) is almost always "exists but not visible to me", which
    // from the user's POV is the same "share with the SA" fix.
    String cls = err.getClass().getSimpleName();
    if ("GoogleJsonResponseException".equals(cls)) {
      String msg = err.getMessage() == null ? "" : err.getMessage();
      return msg.startsWith("403 ") || msg.startsWith("404 ");
    }
    return false;
  }

  @GetMapping("/list/{requesterId}")
  public Flux<AnalysisRequestEntity> listAnalyses(@PathVariable String requesterId) {
    return analysisRequestRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId);
  }

  @GetMapping("/{analysisId}")
  public Mono<ResponseEntity<AnalysisRequestEntity>> getAnalysis(@PathVariable String analysisId) {
    return analysisRequestRepository
        .findById(analysisId)
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  /**
   * Marks an in-flight analysis as CANCELLED. Any already-enqueued Cloud Tasks
   * will still fire — the worker's fetch-metadata handler checks the parent
   * status on entry and short-circuits for non-PROCESSING parents so we don't
   * burn Gemini quota on work the user no longer wants. Safe to call on an
   * already-terminal analysis (no-op).
   */
  @PostMapping("/{analysisId}/cancel")
  public Mono<ResponseEntity<String>> cancelAnalysis(@PathVariable String analysisId) {
    Timestamp now = Timestamp.now();
    return analysisRequestRepository
        .findById(analysisId)
        .flatMap(
            parent -> {
              if ("COMPLETED".equals(parent.getAnalysisStatus())
                  || "CANCELLED".equals(parent.getAnalysisStatus())) {
                return Mono.just(ResponseEntity.ok(parent.getAnalysisStatus()));
              }
              parent.setAnalysisStatus("CANCELLED");
              parent.setUpdatedAt(now);
              return analysisRequestRepository
                  .save(parent)
                  .then(
                      videoMetadataRepository
                          .findByAnalysisId(analysisId)
                          .flatMap(
                              videoMetadata -> {
                                if (!"COMPLETED".equals(videoMetadata.getStatus())) {
                                  videoMetadata.setStatus("CANCELLED");
                                  if (videoMetadata.getErrorMessage() == null) videoMetadata.setErrorMessage("cancelled by user");
                                  return videoMetadataRepository.save(videoMetadata);
                                }
                                return Mono.just(videoMetadata);
                              })
                          .then())
                  .thenReturn(ResponseEntity.ok("CANCELLED"));
            })
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  /**
   * Purges an analysis and everything attached to it — parent request, video
   * inputs, video metadata, and any staged GCS uploads. Intended for
   * user-initiated deletes from the job list; the caller is responsible for
   * asking for confirmation.
   */
  @DeleteMapping("/{analysisId}")
  public Mono<ResponseEntity<String>> deleteAnalysis(@PathVariable String analysisId) {
    UploadUrlService uploads = uploadUrlServiceProvider.getIfAvailable();
    Mono<Void> deleteInputs =
        videoInputRepository
            .findByAnalysisId(analysisId)
            .flatMap(
                videoInputEntity -> {
                  if (uploads != null
                      && videoInputEntity.getGcsObjectId() != null
                      && !videoInputEntity.getGcsObjectId().isEmpty()) {
                    uploads.delete(videoInputEntity.getGcsObjectId());
                  }
                  return videoInputRepository.deleteById(videoInputEntity.getId());
                })
            .then();
    Mono<Void> deleteMetadata =
        videoMetadataRepository
            .findByAnalysisId(analysisId)
            .flatMap(videoMetadata -> videoMetadataRepository.deleteById(videoMetadata.getId()))
            .then();
    return deleteInputs
        .then(deleteMetadata)
        .then(analysisRequestRepository.deleteById(analysisId))
        .thenReturn(ResponseEntity.ok("DELETED"));
  }
}
