package com.bulkaibcd.controller;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.CloudTasksService;
import com.bulkaibcd.service.DriveIngestService;
import com.bulkaibcd.service.GeminiService;
import com.bulkaibcd.service.UploadUrlService;
import org.springframework.beans.factory.ObjectProvider;
import com.google.cloud.Timestamp;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v2/worker")
@RequiredArgsConstructor
@Slf4j
public class AnalysisWorkerController {

  private final VideoMetadataRepository videoMetadataRepository;
  private final VideoInputRepository videoInputRepository;
  private final AnalysisRequestRepository analysisRequestRepository;
  private final GeminiService geminiService;
  private final CloudTasksService cloudTasksService;
  private final ObjectProvider<UploadUrlService> uploadUrlServiceProvider;
  private final ObjectProvider<DriveIngestService> driveIngestServiceProvider;

  // ABCD scoring rubric. Each of A/B/C/D asks Gemini for a single integer 0-100.
  // ASSET_NAME stays as a free-text title used as the display label.
  private static final Map<String, String> PROMPTS =
      Map.of(
          "A_ATTRACT",
              "Watch the video as if you are a viewer seeing it for the first time. On a scale of"
                  + " 0 to 100, how attention-grabbing are the first three seconds? Consider"
                  + " visual hook strength, pacing, and emotional pull. Respond with ONLY an"
                  + " integer between 0 and 100, no explanation.",
          "B_BRAND",
              "On a scale of 0 to 100, how clearly and consistently is the brand identity"
                  + " presented in this video (logo visibility, brand name mentions, brand-colour"
                  + " consistency)? Respond with ONLY an integer between 0 and 100, no"
                  + " explanation.",
          "C_CONNECT",
              "On a scale of 0 to 100, how emotionally engaging is this video? Consider"
                  + " storytelling, relatability, memorable moments, and audience empathy."
                  + " Respond with ONLY an integer between 0 and 100, no explanation.",
          "D_DIRECT",
              "On a scale of 0 to 100, how strong and clear is the call-to-action in this video?"
                  + " Consider whether the viewer knows exactly what to do next and how"
                  + " prominently that instruction is conveyed. Respond with ONLY an integer"
                  + " between 0 and 100, no explanation.",
          "ASSET_NAME",
              "Create a short descriptive title for this video asset (max 8 words). Respond with"
                  + " ONLY the title.");

  private static final List<String> PROMPT_KEYS_STANDARD =
      List.of("A_ATTRACT", "B_BRAND", "C_CONNECT", "D_DIRECT", "ASSET_NAME");

  // "Light" analyses only score the two cheapest-to-reason-about dimensions.
  private static final List<String> PROMPT_KEYS_LIGHT =
      List.of("A_ATTRACT", "B_BRAND", "ASSET_NAME");

  @Data
  public static class TaskRequest {
    private String analysisId;
    private String videoId;
    private String videoUri;
    private String promptType;
  }

  @PostMapping("/prepare")
  public Mono<ResponseEntity<String>> prepareAnalysis(@RequestBody Map<String, String> payload) {
    String analysisId = payload.get("analysisId");
    log.info("Preparing analysis {}", analysisId);

    return videoInputRepository
        .findByAnalysisId(analysisId)
        .collectList()
        .flatMap(
            videos -> {
              if (videos.isEmpty()) {
                log.warn("No video inputs for analysis {}", analysisId);
                return Mono.just(ResponseEntity.ok("No videos to process"));
              }
              return markParentProcessing(analysisId)
                  .then(ingestDriveVideos(videos))
                  .flatMap(
                      ingested ->
                          seedMetadata(ingested)
                              .then(fanOutFetchMetadataTasks(analysisId, ingested))
                              // If every video errored during pre-ingest there
                              // are no per-prompt tasks; run completion once so
                              // the parent flips to COMPLETED right away.
                              .then(checkAnalysisCompletion(analysisId))
                              .thenReturn(ResponseEntity.ok("Analysis prepared")));
            })
        .onErrorResume(
            e -> {
              log.error("Prepare failed for analysis {}", analysisId, e);
              return Mono.just(ResponseEntity.internalServerError().body(e.getMessage()));
            });
  }

  /**
   * Converts any sourceType=drive inputs into GCS-backed ones by streaming the
   * Drive file into the uploads bucket. This runs before seedMetadata so the
   * downstream Gemini prompts get a gs:// URI (which Vertex can fetch) instead
   * of a drive.google.com URL (which it can't). YouTube and file uploads pass
   * through untouched. Failures are persisted to the VideoInputEntity so the
   * per-video fan-in can surface an error rather than stalling.
   */
  private Mono<List<VideoInputEntity>> ingestDriveVideos(List<VideoInputEntity> videos) {
    return Flux.fromIterable(videos)
        .flatMap(
            v -> {
              if (!"drive".equalsIgnoreCase(v.getSourceType())) return Mono.just(v);
              if (v.getGcsObjectId() != null && !v.getGcsObjectId().isEmpty()) {
                return Mono.just(v);
              }
              DriveIngestService svc = driveIngestServiceProvider.getIfAvailable();
              if (svc == null) {
                log.warn("Drive ingest requested but service unavailable for {}", v.getId());
                return Mono.just(v);
              }
              return Mono.fromCallable(() -> svc.ingest(v.getVideoUrl()))
                  .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                  .flatMap(
                      gcsObjectId -> {
                        v.setGcsObjectId(gcsObjectId);
                        return videoInputRepository.save(v);
                      })
                  .onErrorResume(
                      err -> {
                        log.error("Drive ingest failed for {}", v.getVideoUrl(), err);
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
              request.setAnalysisStatus("PROCESSING");
              request.setUpdatedAt(Timestamp.now());
              return analysisRequestRepository.save(request);
            })
        .then();
  }

  private Mono<Void> seedMetadata(List<VideoInputEntity> videos) {
    return Flux.fromIterable(videos)
        .flatMap(
            v -> {
              String docId = v.getAnalysisId() + "_" + v.getVideoId();
              boolean hasError = v.getErrorMessage() != null && !v.getErrorMessage().isEmpty();
              VideoMetadataEntity.VideoMetadataEntityBuilder b =
                  VideoMetadataEntity.builder()
                      .id(docId)
                      .analysisId(v.getAnalysisId())
                      .videoId(v.getVideoId())
                      .videoName(v.getVideoName())
                      .videoUrl(v.getVideoUrl())
                      .thumbnailUrl(v.getThumbnailUrl())
                      .sourceType(v.getSourceType())
                      .status(hasError ? "COMPLETED" : "PROCESSING");
              if (hasError) {
                // Short-circuit: we already know we can't score this video (Drive
                // access denied, etc.). Mark it done with a 0 score + the message
                // so the fan-in can flip the parent to COMPLETED and the report
                // renders an explanation instead of hanging.
                b.errorMessage(v.getErrorMessage())
                    .assetName("")
                    .aScore(0)
                    .bScore(0)
                    .cScore(0)
                    .dScore(0);
              }
              VideoMetadataEntity seed = b.build();
              return videoMetadataRepository
                  .findById(docId)
                  .switchIfEmpty(videoMetadataRepository.save(seed));
            })
        .then();
  }

  private Mono<Void> fanOutFetchMetadataTasks(
      String analysisId, List<VideoInputEntity> videos) {
    return analysisRequestRepository
        .findById(analysisId)
        .map(r -> "light".equalsIgnoreCase(r.getAnalysisType()) ? PROMPT_KEYS_LIGHT : PROMPT_KEYS_STANDARD)
        .defaultIfEmpty(PROMPT_KEYS_STANDARD)
        .flatMap(
            promptKeys ->
                Mono.<Void>fromRunnable(
                    () -> {
                      for (VideoInputEntity v : videos) {
                        // Inputs that hit a pre-ingest error (e.g., Drive file
                        // not shared) already have a COMPLETED metadata row
                        // seeded above — don't burn quota on Gemini calls that
                        // can't succeed.
                        if (v.getErrorMessage() != null && !v.getErrorMessage().isEmpty()) continue;
                        String videoUri = resolveVideoUri(v);
                        for (String promptType : promptKeys) {
                          String payload =
                              String.format(
                                  "{\"analysisId\":\"%s\",\"videoId\":\"%s\",\"videoUri\":\"%s\",\"promptType\":\"%s\"}",
                                  v.getAnalysisId(),
                                  v.getVideoId(),
                                  escape(videoUri),
                                  promptType);
                          try {
                            cloudTasksService.enqueueTask(
                                "/api/v2/worker/fetch-metadata", payload);
                          } catch (IOException e) {
                            log.error(
                                "Failed to enqueue fetch-metadata for {}/{}/{}",
                                v.getAnalysisId(),
                                v.getVideoId(),
                                promptType,
                                e);
                            throw new RuntimeException(e);
                          }
                        }
                      }
                    }))
        .then();
  }

  private static String resolveVideoUri(VideoInputEntity v) {
    // For youtube URLs, videoName is the URL itself. For uploaded files, we
    // would have a gcsObjectId; for IDs we'd resolve via the YouTube/Ads API.
    // File uploads → gs:// path. YouTube/Drive → canonical videoUrl. Fallback to
    // videoName for older records that pre-date the videoUrl column.
    if (v.getGcsObjectId() != null && !v.getGcsObjectId().isEmpty()) {
      return "gs://" + v.getGcsObjectId();
    }
    if (v.getVideoUrl() != null && !v.getVideoUrl().isEmpty()) return v.getVideoUrl();
    return v.getVideoName() == null ? "" : v.getVideoName();
  }

  private static String escape(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  @PostMapping("/fetch-metadata")
  public Mono<ResponseEntity<String>> fetchMetadata(@RequestBody TaskRequest request) {
    String prompt = PROMPTS.get(request.getPromptType());
    if (prompt == null) {
      return Mono.just(ResponseEntity.badRequest().body("Unknown prompt type"));
    }

    // Short-circuit if the user cancelled (or deleted) the parent analysis
    // after this task was enqueued. Cloud Tasks can't retract in-flight work
    // so we pay one Firestore read per call to avoid burning Gemini quota.
    return analysisRequestRepository
        .findById(request.getAnalysisId())
        .map(AnalysisRequestEntity::getAnalysisStatus)
        .defaultIfEmpty("DELETED")
        .flatMap(
            status -> {
              if ("CANCELLED".equals(status) || "DELETED".equals(status)) {
                log.info(
                    "Skipping fetch-metadata for {}/{}/{} — parent is {}",
                    request.getAnalysisId(),
                    request.getVideoId(),
                    request.getPromptType(),
                    status);
                return Mono.just(ResponseEntity.ok("Skipped: parent " + status));
              }
              return runFetchMetadata(request, prompt);
            });
  }

  private Mono<ResponseEntity<String>> runFetchMetadata(TaskRequest request, String prompt) {
    return Mono.fromCallable(
            () -> geminiService.callGemini(request.getVideoUri(), prompt, Optional.empty()))
        .flatMap(
            result -> {
              String id = request.getAnalysisId() + "_" + request.getVideoId();
              return videoMetadataRepository
                  .findById(id)
                  .defaultIfEmpty(
                      VideoMetadataEntity.builder()
                          .id(id)
                          .analysisId(request.getAnalysisId())
                          .videoId(request.getVideoId())
                          .status("PROCESSING")
                          .build())
                  .flatMap(
                      metadata -> {
                        updateMetadataField(metadata, request.getPromptType(), result);
                        boolean justCompleted = false;
                        if (isComplete(metadata, request.getAnalysisId())) {
                          metadata.setStatus("COMPLETED");
                          justCompleted = true;
                        }
                        Mono<Void> postSave =
                            videoMetadataRepository
                                .save(metadata)
                                .then(checkAnalysisCompletion(request.getAnalysisId()));
                        if (justCompleted) {
                          postSave =
                              postSave.then(deleteUploadedSource(request.getAnalysisId(), request.getVideoId()));
                        }
                        return postSave;
                      });
            })
        .then(Mono.just(ResponseEntity.ok("Metadata updated")))
        .onErrorResume(
            e -> {
              // Gemini can fail transiently (quota 429) or permanently (invalid
              // URI). We retry the task via Cloud Tasks up to MAX_FETCH_ATTEMPTS
              // by bumping `attempts` on the metadata row; past that threshold
              // we write a sentinel score + errorMessage so the fan-in can
              // flip the video (and parent analysis) to COMPLETED instead of
              // hanging forever. See user report 2026-04-21.
              log.error("Gemini processing failed", e);
              return recordFailureOrRetry(request, e);
            });
  }

  private static final int MAX_FETCH_ATTEMPTS = 3;

  private Mono<ResponseEntity<String>> recordFailureOrRetry(
      TaskRequest request, Throwable err) {
    String id = request.getAnalysisId() + "_" + request.getVideoId();
    return videoMetadataRepository
        .findById(id)
        .defaultIfEmpty(
            VideoMetadataEntity.builder()
                .id(id)
                .analysisId(request.getAnalysisId())
                .videoId(request.getVideoId())
                .status("PROCESSING")
                .build())
        .flatMap(
            metadata -> {
              int next = metadata.getAttempts() + 1;
              metadata.setAttempts(next);
              String msg = err.getMessage();
              if (msg != null && msg.length() > 300) msg = msg.substring(0, 300);
              metadata.setErrorMessage(msg);
              if (next < MAX_FETCH_ATTEMPTS) {
                // Persist the bumped attempts counter, then ask Cloud Tasks to
                // retry via a 500 response.
                return videoMetadataRepository
                    .save(metadata)
                    .then(
                        Mono.just(
                            ResponseEntity.internalServerError()
                                .body("retrying: " + msg)));
              }
              // Terminal failure for this prompt: fill the field with a zero /
              // empty-string marker so isComplete() can progress, then let the
              // fan-in checker maybe flip the parent to COMPLETED.
              fillTerminalFailure(metadata, request.getPromptType());
              if (isComplete(metadata, request.getAnalysisId())) {
                metadata.setStatus("COMPLETED");
              }
              return videoMetadataRepository
                  .save(metadata)
                  .then(checkAnalysisCompletion(request.getAnalysisId()))
                  .then(Mono.just(ResponseEntity.ok("Recorded terminal failure")));
            });
  }

  private void fillTerminalFailure(VideoMetadataEntity m, String promptType) {
    switch (promptType) {
      case "A_ATTRACT" -> { if (m.getAScore() == null) m.setAScore(0); }
      case "B_BRAND"   -> { if (m.getBScore() == null) m.setBScore(0); }
      case "C_CONNECT" -> { if (m.getCScore() == null) m.setCScore(0); }
      case "D_DIRECT"  -> { if (m.getDScore() == null) m.setDScore(0); }
      case "ASSET_NAME" -> { if (m.getAssetName() == null) m.setAssetName(""); }
    }
  }

  private void updateMetadataField(VideoMetadataEntity metadata, String type, String value) {
    switch (type) {
      case "A_ATTRACT" -> metadata.setAScore(parseScore(value));
      case "B_BRAND" -> metadata.setBScore(parseScore(value));
      case "C_CONNECT" -> metadata.setCScore(parseScore(value));
      case "D_DIRECT" -> metadata.setDScore(parseScore(value));
      case "ASSET_NAME" -> metadata.setAssetName(value == null ? "" : value.trim());
    }
  }

  /** Parse Gemini's "0-100 integer" response robustly. Strips stray characters. */
  private static Integer parseScore(String raw) {
    if (raw == null) return null;
    String digitsOnly = raw.replaceAll("[^0-9]", "");
    if (digitsOnly.isEmpty()) return null;
    try {
      int n = Integer.parseInt(digitsOnly);
      if (n < 0) return 0;
      if (n > 100) return 100;
      return n;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * After all 5 Gemini calls for a file-uploaded video succeed, delete the GCS object
   * so we don't keep raw video bytes around. YouTube/Drive sources have no GCS object.
   */
  private Mono<Void> deleteUploadedSource(String analysisId, String videoId) {
    return videoInputRepository
        .findById(analysisId + "_" + videoId)
        .flatMap(
            input -> {
              if ("file".equals(input.getSourceType()) && input.getGcsObjectId() != null) {
                UploadUrlService svc = uploadUrlServiceProvider.getIfAvailable();
                if (svc != null) svc.delete(input.getGcsObjectId());
              }
              return Mono.empty();
            })
        .then();
  }

  /**
   * A video is COMPLETED when all prompts it was fanned out for have landed their results.
   * Standard analyses require all 4 ABCD scores + asset name. Light analyses only require
   * A + B + asset name. We decide based on the parent request's analysisType.
   * <p>Note: callers don't know the analysisType inline, so we pass the analysisId and peek
   * at the parent request synchronously via a cheap heuristic — if A and B are set and the
   * parent is light, we're done; else require all four.
   */
  private boolean isComplete(VideoMetadataEntity m, String analysisId) {
    if (m.getAssetName() == null) return false;
    boolean abcdAllSet =
        m.getAScore() != null
            && m.getBScore() != null
            && m.getCScore() != null
            && m.getDScore() != null;
    if (abcdAllSet) return true;
    // For light mode: A + B is enough (C and D aren't asked in the first place).
    boolean abOnly = m.getAScore() != null && m.getBScore() != null;
    if (!abOnly) return false;
    AnalysisRequestEntity parent = analysisRequestRepository.findById(analysisId).block();
    return parent != null && "light".equalsIgnoreCase(parent.getAnalysisType());
  }

  private Mono<Void> checkAnalysisCompletion(String analysisId) {
    return videoMetadataRepository
        .findByAnalysisId(analysisId)
        .collectList()
        .flatMap(
            videos -> {
              boolean allDone = videos.stream().allMatch(v -> "COMPLETED".equals(v.getStatus()));
              if (allDone && !videos.isEmpty()) {
                return analysisRequestRepository
                    .findById(analysisId)
                    .flatMap(
                        request -> {
                          request.setAnalysisStatus("COMPLETED");
                          request.setUpdatedAt(Timestamp.now());
                          return analysisRequestRepository.save(request);
                        })
                    .then();
              }
              return Mono.empty();
            });
  }
}
