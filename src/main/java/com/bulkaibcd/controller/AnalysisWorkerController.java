package com.bulkaibcd.controller;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.CloudTasksService;
import com.bulkaibcd.service.DriveIngestService;
import com.bulkaibcd.config.AnalysisConstants;
import com.bulkaibcd.service.GeminiService;
import com.bulkaibcd.service.UploadUrlService;
import com.bulkaibcd.service.FeatureAnalysisService;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import java.util.stream.Collectors;
import com.google.cloud.Timestamp;
import com.google.cloud.aiplatform.v1.JobServiceSettings;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
  private final FeatureAnalysisService featureAnalysisService;
  private final Firestore firestore;

  @Value("${google.cloud.location:us-central1}")
  private String location;

  // ABCD scoring rubric. Each of A/B/C/D asks Gemini for a single integer 0-100.
  // ASSET_NAME stays as a free-text title used as the display label.
  private static final Map<String, String> PROMPTS = Map.of(
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

  private static final List<String> PROMPT_KEYS_STANDARD = List.of("A_ATTRACT", "B_BRAND", "C_CONNECT", "D_DIRECT",
      "ASSET_NAME");

  // "Light" analyses only score the two cheapest-to-reason-about dimensions.
  private static final List<String> PROMPT_KEYS_LIGHT = List.of("A_ATTRACT", "B_BRAND", "ASSET_NAME");

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
    log.info("Worker: Starting preparation for analysisId: {}", analysisId);

    return videoInputRepository
        .findByAnalysisId(analysisId)
        .collectList()
        .flatMap(videos -> processVideos(analysisId, videos))
        .onErrorResume(
            e -> {
              log.error("Prepare failed for analysis {}", analysisId, e);
              return Mono.just(ResponseEntity.internalServerError().body(e.getMessage()));
            });
  }

  private Mono<ResponseEntity<String>> processVideos(String analysisId, List<VideoInputEntity> videos) {
    if (videos.isEmpty()) {
      log.warn("No video inputs for analysis {}", analysisId);
      return Mono.just(ResponseEntity.ok("No videos to process"));
    }
    return markParentProcessing(analysisId)
        .then(ingestDriveVideos(videos))
        .flatMap(
            ingested -> {
              log.info("Analysis {}: Ingested {} videos. Proceeding to seed metadata.", analysisId, ingested.size());
              return seedMetadata(ingested)
                  .then(Mono.fromRunnable(() -> {
                    try {
                      String batchJobId = featureAnalysisService.submitPhase1BatchPredictionJob(analysisId, ingested);
                      if ("NO_VALID_VIDEOS".equals(batchJobId)) {
                        log.info("Worker: No valid videos for Phase 1. Stopping pipeline.");
                        return;
                      }
                      String pollerPayload = String.format(
                          "{\"analysisId\":\"%s\",\"batchJobId\":\"%s\",\"attemptCount\":1}",
                          analysisId, batchJobId);
                      String pollerSuffix = String.format("%s_P1_POLL_attempt_1", analysisId);
                      cloudTasksService.enqueueTask(
                          "/api/v2/worker/check-phase1-batch-status",
                          pollerPayload,
                          pollerSuffix,
                          300 // 5 minutes warm-up delay!
                      );
                      log.info("Worker: Phase 1 Consolidated Batch Job successfully launched. Poller enqueued.");
                    } catch (Exception e) {
                      log.error("Worker: Phase 1 Consolidated Batch Job creation failed!", e);
                      throw new RuntimeException(e);
                    }
                  }))
                  .thenReturn(ResponseEntity.ok("Analysis prepared and Phase 1 batch launched"));
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
              if (!"drive".equalsIgnoreCase(v.getSourceType()))
                return Mono.just(v);
              log.info("Analysis {}: Processing Drive ingest for video {}", v.getAnalysisId(), v.getVideoId());
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
                        log.info("Analysis {}: Video {} successfully ingested to GCS: {}", v.getAnalysisId(),
                            v.getVideoId(), gcsObjectId);
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
              log.info("Analysis {}: Seeding metadata placeholder for video {}", v.getAnalysisId(), v.getVideoId());
              String docId = v.getAnalysisId() + "_" + v.getVideoId();
              boolean hasError = v.getErrorMessage() != null && !v.getErrorMessage().isEmpty();
              VideoMetadataEntity.VideoMetadataEntityBuilder b = VideoMetadataEntity.builder()
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
    return Mono.<Void>fromRunnable(
        () -> {
          for (VideoInputEntity v : videos) {
            // Inputs that hit a pre-ingest error (e.g., Drive file
            // not shared) already have a COMPLETED metadata row
            // seeded in db — don't burn quota on Gemini calls.
            if (v.getErrorMessage() != null && !v.getErrorMessage().isEmpty())
              continue;
            String videoUri = resolveVideoUri(v);
            for (String metadataType : AnalysisConstants.METADATA_TYPES) {
              String payload = String.format(
                  "{\"analysisId\":\"%s\",\"videoId\":\"%s\",\"videoUri\":\"%s\",\"promptType\":\"%s\"}",
                  v.getAnalysisId(),
                  v.getVideoId(),
                  escape(videoUri),
                  metadataType);

              // Deterministic suffix: {analysisId}_{videoId}_{metadataType}
              String taskSuffix = String.format("%s_%s_%s", v.getAnalysisId(), v.getVideoId(), metadataType);

              log.info("Analysis {}: Enqueuing raw metadata extraction task for video {} / prompt {}",
                  analysisId, v.getVideoId(), metadataType);
              try {
                cloudTasksService.enqueueTask(
                    "/api/v2/worker/extract-raw-metadata", payload, taskSuffix, null);
              } catch (IOException e) {
                log.error("Analysis {}: Failed to enqueue task for video {} / prompt {}",
                    v.getAnalysisId(), v.getVideoId(), metadataType, e);
                throw new RuntimeException(e);
              }
            }
          }
        })
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
    if (v.getVideoUrl() != null && !v.getVideoUrl().isEmpty())
      return v.getVideoUrl();
    return v.getVideoName() == null ? "" : v.getVideoName();
  }

  private static String escape(String s) {
    return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  @PostMapping("/fetch-metadata")
  public Mono<ResponseEntity<String>> fetchMetadata(
      @RequestBody TaskRequest request,
      @RequestHeader(value = "X-CloudTasks-TaskExecutionCount", defaultValue = "0") int executionCount) {
    log.info("Worker: Polling fetch-metadata task for analysisId: {}, videoId: {}, promptType: {}. Execution: {}",
        request.getAnalysisId(), request.getVideoId(), request.getPromptType(), executionCount);

    String prompt = PROMPTS.get(request.getPromptType());
    if (prompt == null) {
      log.error("Worker: Unknown prompt type received: {}", request.getPromptType());
      return Mono.just(ResponseEntity.badRequest().body("Unknown prompt type"));
    }

    return analysisRequestRepository
        .findById(request.getAnalysisId())
        .map(AnalysisRequestEntity::getAnalysisStatus)
        .defaultIfEmpty("DELETED")
        .flatMap(
            status -> {
              if ("CANCELLED".equals(status) || "DELETED".equals(status)) {
                log.info("Worker: Skipping task for {}/{}/{} — parent status is {}",
                    request.getAnalysisId(),
                    request.getVideoId(),
                    request.getPromptType(),
                    status);
                return Mono.just(ResponseEntity.ok("Skipped: parent " + status));
              }
              return runFetchMetadata(request, prompt, executionCount);
            });
  }

  private boolean isScoreAlreadySet(VideoMetadataEntity m, String type) {
    switch (type) {
      case "A_ATTRACT" -> {
        return m.getAScore() != null;
      }
      case "B_BRAND" -> {
        return m.getBScore() != null;
      }
      case "C_CONNECT" -> {
        return m.getCScore() != null;
      }
      case "D_DIRECT" -> {
        return m.getDScore() != null;
      }
      case "ASSET_NAME" -> {
        return m.getAssetName() != null && !m.getAssetName().isEmpty();
      }
      default -> {
        return false;
      }
    }
  }

  private Mono<ResponseEntity<String>> runFetchMetadata(TaskRequest request, String prompt, int executionCount) {
    String metadataId = request.getAnalysisId() + "_" + request.getVideoId();
    String promptType = request.getPromptType();

    return videoMetadataRepository
        .findById(metadataId)
        .defaultIfEmpty(
            VideoMetadataEntity.builder()
                .id(metadataId)
                .analysisId(request.getAnalysisId())
                .videoId(request.getVideoId())
                .status("PROCESSING")
                .build())
        .flatMap(
            metadata -> {
              // 1. Idempotency check: Is the score already set?
              if (isScoreAlreadySet(metadata, promptType)) {
                log.info("Worker: Prompt {} for video {}/{} is already completed. Skipping.",
                    promptType, request.getAnalysisId(), request.getVideoId());
                return Mono.just(ResponseEntity.ok("Already processed"));
              }

              log.info("Worker: Processing fetch-metadata request for {}/{}/{}. Attempt (Execution Count): {}",
                  request.getAnalysisId(), request.getVideoId(), promptType, executionCount);

              return Mono.fromCallable(
                  () -> geminiService.callGemini(request.getVideoUri(), prompt, Optional.empty()))
                  .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                  .flatMap(
                      result -> {
                        log.info("Worker: Gemini response for {}/{}/{}: {}", request.getAnalysisId(),
                            request.getVideoId(), promptType, result);

                        // Load again to get latest state (avoid overwriting concurrent updates)
                        return videoMetadataRepository.findById(metadataId)
                            .defaultIfEmpty(metadata)
                            .flatMap(latestMetadata -> {
                              updateMetadataField(latestMetadata, promptType, result);

                              return isCompleteReactive(latestMetadata, request.getAnalysisId())
                                  .flatMap(completed -> {
                                    boolean justCompleted = false;
                                    if (completed) {
                                      log.info("Worker: Video {}/{} is now COMPLETED.", request.getAnalysisId(),
                                          request.getVideoId());
                                      latestMetadata.setStatus("COMPLETED");
                                      justCompleted = true;
                                    }
                                    Mono<Void> postSave = videoMetadataRepository
                                        .save(latestMetadata)
                                        .then(checkAnalysisCompletion(request.getAnalysisId()));
                                    if (justCompleted) {
                                      log.info("Worker: Cleaning up source for {}/{}", request.getAnalysisId(),
                                          request.getVideoId());
                                      postSave = postSave
                                          .then(deleteUploadedSource(request.getAnalysisId(), request.getVideoId()));
                                    }
                                    return postSave;
                                  });
                            });
                      })
                  .then(Mono.just(ResponseEntity.ok("Metadata updated")))
                  .onErrorResume(
                      e -> {
                        log.error("Worker: Gemini processing failed for {}/{}/{} on execution {}.",
                            request.getAnalysisId(), request.getVideoId(), promptType, executionCount, e);
                        return recordFailureOrRetryReactive(request, e, executionCount);
                      });
            });
  }

  private static final int MAX_FETCH_ATTEMPTS = 3;

  private Mono<ResponseEntity<String>> recordFailureOrRetryReactive(
      TaskRequest request, Throwable err, int executionCount) {
    String id = request.getAnalysisId() + "_" + request.getVideoId();
    String promptType = request.getPromptType();

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
              String msg = err.getMessage();
              if (msg != null && msg.length() > 300)
                msg = msg.substring(0, 300);

              // executionCount starts at 0 (1st attempt), retry is 1 (2nd attempt), and retry
              // limit is 2 (3rd attempt)
              if (executionCount < (MAX_FETCH_ATTEMPTS - 1)) {
                int humanAttemptNumber = executionCount + 1;
                log.info(
                    "Worker: Failure logged on attempt {}/{} for {}/{}/{}. Scheduling retry via Cloud Tasks. Error: {}",
                    humanAttemptNumber, MAX_FETCH_ATTEMPTS, request.getAnalysisId(), request.getVideoId(), promptType,
                    msg);

                // Return 500 so Cloud Tasks automatically retries
                return videoMetadataRepository
                    .save(metadata)
                    .then(Mono.just(ResponseEntity.internalServerError().body("retrying: " + msg)));
              }

              log.warn("Worker: Terminal failure for {}/{}/{} reached after max attempts ({}). Saving placeholder.",
                  request.getAnalysisId(), request.getVideoId(), promptType, MAX_FETCH_ATTEMPTS);

              fillTerminalFailure(metadata, promptType);
              metadata.setErrorMessage(msg);

              return isCompleteReactive(metadata, request.getAnalysisId())
                  .flatMap(
                      completed -> {
                        if (completed) {
                          metadata.setStatus("COMPLETED");
                        } else {
                          // If this was a terminal failure of the final attempt, and the overall video
                          // isn't complete,
                          // we mark the video metadata entity as FAILED to prevent the batch from
                          // hanging.
                          metadata.setStatus("FAILED");
                        }
                        return videoMetadataRepository
                            .save(metadata)
                            .then(checkAnalysisCompletion(request.getAnalysisId()))
                            .then(Mono.just(ResponseEntity.ok("Recorded terminal failure")));
                      });
            });
  }

  private void fillTerminalFailure(VideoMetadataEntity m, String promptType) {
    switch (promptType) {
      case "A_ATTRACT" -> {
        if (m.getAScore() == null)
          m.setAScore(0);
      }
      case "B_BRAND" -> {
        if (m.getBScore() == null)
          m.setBScore(0);
      }
      case "C_CONNECT" -> {
        if (m.getCScore() == null)
          m.setCScore(0);
      }
      case "D_DIRECT" -> {
        if (m.getDScore() == null)
          m.setDScore(0);
      }
      case "ASSET_NAME" -> {
        if (m.getAssetName() == null)
          m.setAssetName("");
      }
    }
  }

  private void updateMetadataField(VideoMetadataEntity metadata, String type, String value) {
    log.info("Worker: Updating field {} for video {} with value: {}", type, metadata.getVideoId(), value);
    switch (type) {
      case "A_ATTRACT" -> metadata.setAScore(parseScore(value));
      case "B_BRAND" -> metadata.setBScore(parseScore(value));
      case "C_CONNECT" -> metadata.setCScore(parseScore(value));
      case "D_DIRECT" -> metadata.setDScore(parseScore(value));
      case "ASSET_NAME" -> metadata.setAssetName(value == null ? "" : value.trim());
    }
  }

  /**
   * Parse Gemini's "0-100 integer" response robustly. Strips stray characters.
   */
  private static Integer parseScore(String raw) {
    if (raw == null)
      return null;
    String digitsOnly = raw.replaceAll("[^0-9]", "");
    if (digitsOnly.isEmpty())
      return null;
    try {
      int n = Integer.parseInt(digitsOnly);
      if (n < 0)
        return 0;
      if (n > 100)
        return 100;
      return n;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * After all 5 Gemini calls for a file-uploaded video succeed, delete the GCS
   * object
   * so we don't keep raw video bytes around. YouTube/Drive sources have no GCS
   * object.
   */
  private Mono<Void> deleteUploadedSource(String analysisId, String videoId) {
    return videoInputRepository
        .findById(analysisId + "_" + videoId)
        .flatMap(
            input -> {
              if ("file".equals(input.getSourceType()) && input.getGcsObjectId() != null) {
                UploadUrlService svc = uploadUrlServiceProvider.getIfAvailable();
                if (svc != null)
                  svc.delete(input.getGcsObjectId());
              }
              return Mono.empty();
            })
        .then();
  }

  /**
   * A video is COMPLETED when all prompts it was fanned out for have landed their
   * results.
   * Standard analyses require all 4 ABCD scores + asset name. Light analyses only
   * require
   * A + B + asset name. We decide based on the parent request's analysisType.
   */
  private Mono<Boolean> isCompleteReactive(VideoMetadataEntity m, String analysisId) {
    if (m.getAssetName() == null) {
      return Mono.just(false);
    }

    boolean abcdAllSet = m.getAScore() != null
        && m.getBScore() != null
        && m.getCScore() != null
        && m.getDScore() != null;
    if (abcdAllSet) {
      return Mono.just(true);
    }

    // For light mode: A + B is enough (C and D aren't asked in the first place).
    boolean abOnly = m.getAScore() != null && m.getBScore() != null;
    if (!abOnly) {
      return Mono.just(false);
    }

    return analysisRequestRepository
        .findById(analysisId)
        .map(parent -> parent != null && "light".equalsIgnoreCase(parent.getAnalysisType()))
        .defaultIfEmpty(false); // If parent not found, assume not light or not complete
  }

  private Mono<Void> checkAnalysisCompletion(String analysisId) {
    return videoInputRepository.findByAnalysisId(analysisId)
        .collectList()
        .flatMap(inputs -> {
          if (inputs.isEmpty()) {
            return Mono.empty();
          }

          // Stream metadata reactively with O(1) memory and gRPC throttling
          return Flux.fromIterable(inputs)
              .flatMap(input -> {
                String metadataId = analysisId + "_" + input.getVideoId();
                return videoMetadataRepository.findById(metadataId)
                    .defaultIfEmpty(
                        VideoMetadataEntity.builder()
                            .status("PROCESSING")
                            .build());
              }, 8)
              .all(v -> "COMPLETED".equals(v.getStatus()) || "FAILED".equals(v.getStatus()))
              .flatMap(allDone -> {
                if (allDone) {
                  log.info(
                      "Worker: Strongly consistent check: All {} videos for analysis {} are COMPLETED/FAILED. Marking parent as COMPLETED.",
                      inputs.size(), analysisId);
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
        });
  }

  @PostMapping("/extract-raw-metadata")
  public Mono<ResponseEntity<String>> extractRawMetadata(
      @RequestBody TaskRequest request,
      @RequestHeader(value = "X-CloudTasks-TaskExecutionCount", defaultValue = "0") int executionCount) {
    log.info("Worker: Polling extract-raw-metadata task for analysisId: {}, videoId: {}, promptType: {}. Execution: {}",
        request.getAnalysisId(), request.getVideoId(), request.getPromptType(), executionCount);

    String prompt = AnalysisConstants.RAW_PROMPT_MAP.get(request.getPromptType());
    if (prompt == null) {
      log.error("Worker: Unknown raw prompt type received: {}", request.getPromptType());
      return Mono.just(ResponseEntity.badRequest().body("Unknown raw prompt type"));
    }

    return analysisRequestRepository
        .findById(request.getAnalysisId())
        .map(AnalysisRequestEntity::getAnalysisStatus)
        .defaultIfEmpty("DELETED")
        .flatMap(
            status -> {
              if ("CANCELLED".equals(status) || "DELETED".equals(status)) {
                log.info("Worker: Skipping extraction task for {}/{}/{} — parent status is {}",
                    request.getAnalysisId(),
                    request.getVideoId(),
                    request.getPromptType(),
                    status);
                return Mono.just(ResponseEntity.ok("Skipped: parent " + status));
              }
              return runExtractRawMetadata(request, prompt, executionCount);
            });
  }

  @PostMapping("/start-feature-analysis")
  public Mono<ResponseEntity<String>> startFeatureAnalysis(@RequestBody Map<String, String> payload) {
    String analysisId = payload.get("analysisId");
    log.info("Worker: Starting Phase 2 consolidated feature analysis dispatcher for run: {}", analysisId);

    return analysisRequestRepository
        .findById(analysisId)
        .flatMap(
            parent -> {
              if ("CANCELLED".equals(parent.getAnalysisStatus()) || "DELETED".equals(parent.getAnalysisStatus())) {
                log.info("Worker: Skipping Phase 2 start — parent status is {}", parent.getAnalysisStatus());
                return Mono.just(ResponseEntity.ok("Skipped: parent " + parent.getAnalysisStatus()));
              }

              // 1. Idempotency Check: Verify if parent already triggered batch prediction
              if ("BATCH_QUEUED".equals(parent.getAnalysisStatus()) || "COMPLETED".equals(parent.getAnalysisStatus())) {
                log.info(
                    "Worker: Batch prediction already queued or completed for analysis: {}. Skipping duplicate trigger.",
                    analysisId);
                return Mono.just(ResponseEntity.ok("Already triggered"));
              }

              // 2. Fetch all video inputs
              return videoInputRepository.findByAnalysisId(analysisId)
                  .collectList()
                  .flatMap(
                      inputs -> {
                        // Filter out failed inputs (e.g. drive ingest fails)
                        List<String> validVideoIds = inputs.stream()
                            .filter(v -> v.getErrorMessage() == null || v.getErrorMessage().isEmpty())
                            .map(VideoInputEntity::getVideoId)
                            .collect(Collectors.toList());

                        if (validVideoIds.isEmpty()) {
                          log.warn("Worker: No valid videos to audit for batch run: {}", analysisId);
                          parent.setAnalysisStatus("COMPLETED");
                          parent.setUpdatedAt(Timestamp.now());
                          return analysisRequestRepository.save(parent)
                              .thenReturn(ResponseEntity.ok("No valid videos to analyze"));
                        }

                        // 3. Load fanned-in metadata placeholders
                        return Flux.fromIterable(validVideoIds)
                            .flatMap(
                                videoId -> videoMetadataRepository.findById(analysisId + "_" + videoId)
                                    .defaultIfEmpty(VideoMetadataEntity.builder().status("PROCESSING").build()))
                            .collectList()
                            .flatMap(
                                metadataList -> {
                                  // 4. Trigger Programmatic Vertex Batch Prediction
                                  try {
                                    String batchJobId = featureAnalysisService.submitBatchPredictionJob(
                                        analysisId,
                                        metadataList,
                                        parent.getAnalysisType(),
                                        parent.getBrandName());

                                    // Update all video statuses to BATCH_QUEUED
                                    for (VideoMetadataEntity v : metadataList) {
                                      v.setStatus("BATCH_QUEUED");
                                    }

                                    // Update parent request status
                                    parent.setAnalysisStatus("BATCH_QUEUED");
                                    parent.setUpdatedAt(Timestamp.now());

                                    Mono<Void> updateDb = Flux.fromIterable(metadataList)
                                        .flatMap(v -> Mono.fromCallable(() -> firestore.collection("video_metadata").document(v.getId()).update("status", "BATCH_QUEUED").get())
                                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()), 20)
                                        .then(analysisRequestRepository.save(parent))
                                        .then();

                                    // 5. Enqueue Async Poller in Google Cloud Tasks with a 5-minute warm-up delay
                                    // (Attempt = 1)
                                    String pollerPayload = String.format(
                                        "{\"analysisId\":\"%s\",\"batchJobId\":\"%s\",\"attemptCount\":1}",
                                        analysisId, batchJobId);
                                    String pollerSuffix = String.format("%s_POLL_attempt_1", analysisId);

                                    return updateDb.then(
                                        Mono.fromRunnable(
                                            () -> {
                                              try {
                                                cloudTasksService.enqueueTask(
                                                    "/api/v2/worker/check-batch-status",
                                                    pollerPayload,
                                                    pollerSuffix,
                                                    300 // 5 minutes warm-up delay!
                                                );
                                                log.info(
                                                    "Worker: Consolidated Batch Job successfully launched. Poller enqueued.");
                                              } catch (IOException e) {
                                                log.error("Worker: Failed to enqueue poller task", e);
                                                throw new RuntimeException(e);
                                              }
                                            }))
                                        .thenReturn(ResponseEntity.ok("Batch predicted job launched successfully"));

                                  } catch (Exception e) {
                                    log.error("Worker: Consolidated Batch Job creation failed!", e);
                                    parent.setAnalysisStatus("FAILED");
                                    parent.setUpdatedAt(Timestamp.now());
                                    return analysisRequestRepository.save(parent)
                                        .thenReturn(ResponseEntity.internalServerError()
                                            .body("Failed to launch batch predictions: " + e.getMessage()));
                                  }
                                });
                      });
            })
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  private Mono<ResponseEntity<String>> runExtractRawMetadata(TaskRequest request, String prompt, int executionCount) {
    String metadataId = request.getAnalysisId() + "_" + request.getVideoId();
    String promptType = request.getPromptType();

    return videoMetadataRepository
        .findById(metadataId)
        .defaultIfEmpty(
            VideoMetadataEntity.builder()
                .id(metadataId)
                .analysisId(request.getAnalysisId())
                .videoId(request.getVideoId())
                .status("PROCESSING")
                .build())
        .flatMap(
            metadata -> {
              // 1. Idempotency check: Is the field already set?
              if (isRawMetadataFieldPopulated(metadata, promptType)) {
                log.info("Worker: Raw metadata {} for video {}/{} is already completed. Skipping.",
                    promptType, request.getAnalysisId(), request.getVideoId());
                return Mono.just(ResponseEntity.ok("Already processed"));
              }

              log.info("Worker: Processing extract-raw-metadata request for {}/{}/{}. Attempt (Execution Count): {}",
                  request.getAnalysisId(), request.getVideoId(), promptType, executionCount);

              return Mono.fromCallable(
                  () -> geminiService.callGemini(request.getVideoUri(), prompt, Optional.empty()))
                  .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                  .flatMap(
                      result -> {
                        log.info("Worker: Gemini response raw for {}/{}/{}: {}", request.getAnalysisId(),
                            request.getVideoId(), promptType, result);

                        DocumentReference docRef = firestore.collection("video_metadata").document(metadataId);

                        return Mono.fromCallable(() -> firestore.runTransaction(transaction -> {
                          DocumentSnapshot snapshot = transaction.get(docRef).get();
                          VideoMetadataEntity latestMetadata;
                          if (snapshot.exists()) {
                            latestMetadata = snapshot.toObject(VideoMetadataEntity.class);
                          } else {
                            latestMetadata = metadata;
                          }
                          updateRawMetadataField(latestMetadata, promptType, result);

                          boolean rawPhaseDone = isMetadataPhaseComplete(latestMetadata);
                          if (rawPhaseDone) {
                            log.info(
                                "Worker: Video {}/{} has finished Phase 1 (14 metadata fields fanned-in). Promoting status to METADATA_EXTRACTED.",
                                request.getAnalysisId(), request.getVideoId());
                            latestMetadata.setStatus("METADATA_EXTRACTED");
                          }

                          transaction.set(docRef, latestMetadata);
                          return rawPhaseDone;
                        }).get())
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .flatMap(rawPhaseDone -> {
                          if (rawPhaseDone) {
                            return checkMetadataPhaseBatchCompletion(request.getAnalysisId());
                          }
                          return Mono.empty();
                        });
                      })
                  .then(Mono.just(ResponseEntity.ok("Raw metadata updated")))
                  .onErrorResume(
                      e -> {
                        log.error("Worker: Gemini raw extraction failed for {}/{}/{} on execution {}.",
                            request.getAnalysisId(), request.getVideoId(), promptType, executionCount, e);
                        return recordRawFailureOrRetry(request, e, executionCount);
                      });
            });
  }

  private Mono<ResponseEntity<String>> recordRawFailureOrRetry(
      TaskRequest request, Throwable err, int executionCount) {
    String id = request.getAnalysisId() + "_" + request.getVideoId();
    String promptType = request.getPromptType();

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
              String rawMsg = err.getMessage();
              final String msg = (rawMsg != null && rawMsg.length() > 300) ? rawMsg.substring(0, 300) : rawMsg;

              if (executionCount < (MAX_FETCH_ATTEMPTS - 1)) {
                int humanAttemptNumber = executionCount + 1;
                log.info(
                    "Worker: Raw extraction failure on attempt {}/{} for {}/{}/{}. Scheduling GTasks retry. Error: {}",
                    humanAttemptNumber, MAX_FETCH_ATTEMPTS, request.getAnalysisId(), request.getVideoId(), promptType,
                    msg);

                return videoMetadataRepository
                    .save(metadata)
                    .then(Mono.just(ResponseEntity.internalServerError().body("retrying: " + msg)));
              }

              log.warn(
                  "Worker: Terminal raw extraction failure for {}/{}/{} reached after max attempts ({}). Saving empty placeholder.",
                  request.getAnalysisId(), request.getVideoId(), promptType, MAX_FETCH_ATTEMPTS);

              DocumentReference docRef = firestore.collection("video_metadata").document(id);

              return Mono.fromCallable(() -> firestore.runTransaction(transaction -> {
                DocumentSnapshot snapshot = transaction.get(docRef).get();
                VideoMetadataEntity latestMetadata;
                if (snapshot.exists()) {
                  latestMetadata = snapshot.toObject(VideoMetadataEntity.class);
                } else {
                  latestMetadata = metadata;
                }
                fillTerminalRawFailure(latestMetadata, promptType);
                latestMetadata.setErrorMessage(msg);

                boolean rawPhaseDone = isMetadataPhaseComplete(latestMetadata);
                if (rawPhaseDone) {
                  log.info("Worker: Video {}/{} has finished Phase 1 on terminal fallback. Status -> METADATA_EXTRACTED.",
                      request.getAnalysisId(), request.getVideoId());
                  latestMetadata.setStatus("METADATA_EXTRACTED");
                } else {
                  latestMetadata.setStatus("FAILED");
                }

                transaction.set(docRef, latestMetadata);
                return rawPhaseDone;
              }).get())
              .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
              .flatMap(rawPhaseDone -> {
                if (rawPhaseDone) {
                  return checkMetadataPhaseBatchCompletion(request.getAnalysisId());
                }
                return Mono.empty();
              })
              .then(Mono.just(ResponseEntity.ok("Recorded terminal extraction failure")));
            });
  }

  private boolean isRawMetadataFieldPopulated(VideoMetadataEntity m, String type) {
    return switch (type) {
      case "BRAND" -> m.getBrand() != null;
      case "PRODUCT" -> m.getProduct() != null;
      case "LANGUAGE" -> m.getVideoLanguage() != null;
      case "VERTICAL" -> m.getVertical() != null;
      case "ASSET_NAME" -> m.getAssetName() != null && !m.getAssetName().isEmpty();
      case "SPEECH_TRANSCRIPTION" -> m.getSpeech() != null;
      case "TEXT_DETECTION" -> m.getText() != null;
      case "SHOT_CHANGE_DETECTION" -> m.getShot() != null;
      case "LOGO_RECOGNITION" -> m.getLogo() != null;
      case "OBJECT_TRACKING" -> m.getObjects() != null;
      case "FACE_DETECTION" -> m.getFace() != null;
      case "PERSON_DETECTION" -> m.getPerson() != null;
      case "LABEL_DETECTION" -> m.getLabelName() != null;
      case "EXPLICIT_CONTENT_DETECTION" -> m.getExplicit() != null;
      default -> false;
    };
  }

  private void updateRawMetadataField(VideoMetadataEntity m, String type, String val) {
    String cleaned = stripQuotes(val);
    switch (type) {
      case "BRAND" -> m.setBrand(cleaned);
      case "PRODUCT" -> m.setProduct(cleaned);
      case "LANGUAGE" -> m.setVideoLanguage(cleaned);
      case "VERTICAL" -> m.setVertical(cleaned);
      case "ASSET_NAME" -> m.setAssetName(cleaned);
      case "SPEECH_TRANSCRIPTION" -> m.setSpeech(cleaned);
      case "TEXT_DETECTION" -> m.setText(cleaned);
      case "SHOT_CHANGE_DETECTION" -> m.setShot(cleaned);
      case "LOGO_RECOGNITION" -> m.setLogo(cleaned);
      case "OBJECT_TRACKING" -> m.setObjects(cleaned);
      case "FACE_DETECTION" -> m.setFace(cleaned);
      case "PERSON_DETECTION" -> m.setPerson(cleaned);
      case "LABEL_DETECTION" -> m.setLabelName(cleaned);
      case "EXPLICIT_CONTENT_DETECTION" -> m.setExplicit(cleaned);
    }
  }

  private boolean isMetadataPhaseComplete(VideoMetadataEntity m) {
    return m.getBrand() != null
        && m.getProduct() != null
        && m.getVideoLanguage() != null
        && m.getVertical() != null
        && m.getAssetName() != null && !m.getAssetName().isEmpty()
        && m.getSpeech() != null
        && m.getText() != null
        && m.getShot() != null
        && m.getLogo() != null
        && m.getObjects() != null
        && m.getFace() != null
        && m.getPerson() != null
        && m.getLabelName() != null
        && m.getExplicit() != null;
  }

  private void fillTerminalRawFailure(VideoMetadataEntity m, String promptType) {
    switch (promptType) {
      case "BRAND" -> {
        if (m.getBrand() == null)
          m.setBrand("");
      }
      case "PRODUCT" -> {
        if (m.getProduct() == null)
          m.setProduct("");
      }
      case "LANGUAGE" -> {
        if (m.getVideoLanguage() == null)
          m.setVideoLanguage("zxx");
      }
      case "VERTICAL" -> {
        if (m.getVertical() == null)
          m.setVertical("");
      }
      case "ASSET_NAME" -> {
        if (m.getAssetName() == null)
          m.setAssetName("");
      }
      case "SPEECH_TRANSCRIPTION" -> {
        if (m.getSpeech() == null)
          m.setSpeech("[]");
      }
      case "TEXT_DETECTION" -> {
        if (m.getText() == null)
          m.setText("[]");
      }
      case "SHOT_CHANGE_DETECTION" -> {
        if (m.getShot() == null)
          m.setShot("[]");
      }
      case "LOGO_RECOGNITION" -> {
        if (m.getLogo() == null)
          m.setLogo("[]");
      }
      case "OBJECT_TRACKING" -> {
        if (m.getObjects() == null)
          m.setObjects("[]");
      }
      case "FACE_DETECTION" -> {
        if (m.getFace() == null)
          m.setFace("[]");
      }
      case "PERSON_DETECTION" -> {
        if (m.getPerson() == null)
          m.setPerson("[]");
      }
      case "LABEL_DETECTION" -> {
        if (m.getLabelName() == null)
          m.setLabelName("[]");
      }
      case "EXPLICIT_CONTENT_DETECTION" -> {
        if (m.getExplicit() == null)
          m.setExplicit("[]");
      }
    }
  }

  @Nullable
  private static String stripQuotes(@Nullable String text) {
    if (text == null) {
      return null;
    }
    String trimmed = text.trim();
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }

  private Mono<Void> checkMetadataPhaseBatchCompletion(String analysisId) {
    return videoInputRepository.findByAnalysisId(analysisId)
        .collectList()
        .flatMap(inputs -> {
          if (inputs.isEmpty())
            return Mono.empty();

          // Stream metadata reactively with O(1) memory and gRPC throttling
          return Flux.fromIterable(inputs)
              .flatMap(input -> {
                String metadataId = analysisId + "_" + input.getVideoId();
                return videoMetadataRepository.findById(metadataId)
                    .defaultIfEmpty(VideoMetadataEntity.builder().status("PROCESSING").build());
              }, 8)
              .all(v -> "METADATA_EXTRACTED".equals(v.getStatus())
                  || "COMPLETED".equals(v.getStatus())
                  || "FAILED".equals(v.getStatus()))
              .flatMap(allReady -> {
                if (allReady) {
                  log.info(
                      "Worker: Dynamic Gate Verified: All {} videos in analysis {} finished Phase 1 (Metadata Extraction). Triggering Phase 2 (Feature Analysis).",
                      inputs.size(), analysisId);

                  String taskSuffix = String.format("%s_START_FEATURE_ANALYSIS", analysisId);
                  String payload = String.format("{\"analysisId\":\"%s\"}", analysisId);

                  try {
                    cloudTasksService.enqueueTask("/api/v2/worker/start-feature-analysis", payload, taskSuffix, null);
                    log.info("Worker: Phase 2 start task successfully enqueued for run: {}", analysisId);
                  } catch (IOException e) {
                    log.error("Worker: Failed to trigger Phase 2 for run: {}", analysisId, e);
                    return Mono.error(e);
                  }
                }
                return Mono.empty();
              });
        });
  }

  @PostMapping("/check-batch-status")
  public Mono<ResponseEntity<String>> checkBatchStatus(@RequestBody PollRequest request) {
    log.info("Worker: Polling check-batch-status for analysisId: {}, batchJobId: {}, attempt: {}",
        request.getAnalysisId(), request.getBatchJobId(), request.getAttemptCount());

    return analysisRequestRepository
        .findById(request.getAnalysisId())
        .map(AnalysisRequestEntity::getAnalysisStatus)
        .defaultIfEmpty("DELETED")
        .flatMap(
            status -> {
              if ("CANCELLED".equals(status) || "DELETED".equals(status)) {
                log.info("Worker: Skipping batch polling check — parent status is {}", status);
                return Mono.just(ResponseEntity.ok("Skipped: parent " + status));
              }
              return runCheckBatchStatus(request);
            });
  }

  private Mono<ResponseEntity<String>> runCheckBatchStatus(PollRequest request) {
    String analysisId = request.getAnalysisId();
    String batchJobId = request.getBatchJobId();
    int attempts = request.getAttemptCount();

    return Mono.fromCallable(
        () -> {
          String regionEndpoint = location + "-aiplatform.googleapis.com:443";
          JobServiceSettings settings = JobServiceSettings.newBuilder()
              .setEndpoint(regionEndpoint)
              .build();

          try (com.google.cloud.aiplatform.v1.JobServiceClient client = com.google.cloud.aiplatform.v1.JobServiceClient
              .create(settings)) {
            com.google.cloud.aiplatform.v1.BatchPredictionJob job = client.getBatchPredictionJob(batchJobId);
            com.google.cloud.aiplatform.v1.JobState state = job.getState();
            com.google.rpc.Status error = job.getError();

            log.info("Worker: Batch Prediction Job {} for analysisId {} is in state: {}",
                batchJobId, analysisId, state);

            if (state == com.google.cloud.aiplatform.v1.JobState.JOB_STATE_PENDING
                || state == com.google.cloud.aiplatform.v1.JobState.JOB_STATE_QUEUED
                || state == com.google.cloud.aiplatform.v1.JobState.JOB_STATE_RUNNING) {

              int nextAttempt = attempts + 1;
              int delay = getBackoffDelay(nextAttempt);

              String nextPayload = String.format(
                  "{\"analysisId\":\"%s\",\"batchJobId\":\"%s\",\"attemptCount\":%d}",
                  analysisId, batchJobId, nextAttempt);
              String nextSuffix = String.format("%s_POLL_attempt_%d", analysisId, nextAttempt);

              log.info("Worker: Job still in-flight. Scheduling next check in {}s. Suffix: {}", delay, nextSuffix);
              try {
                cloudTasksService.enqueueTask("/api/v2/worker/check-batch-status", nextPayload, nextSuffix, delay);
              } catch (IOException e) {
                log.error("Worker: Failed to re-enqueue status check", e);
                throw new RuntimeException(e);
              }
              return "RUNNING";

            } else if (state == com.google.cloud.aiplatform.v1.JobState.JOB_STATE_SUCCEEDED) {
              String outputGcsUri = job.getOutputInfo().getGcsOutputDirectory();
              log.info("Worker: Batch Job Succeeded! Stage output staged at: {}. Enqueuing results parser.",
                  outputGcsUri);

              String processPayload = String.format(
                  "{\"analysisId\":\"%s\",\"gcsUri\":\"%s\"}",
                  analysisId, outputGcsUri);
              String processSuffix = String.format("%s_PROCESS_RESULTS", analysisId);

              try {
                cloudTasksService.enqueueTask("/api/v2/worker/process-batch-results", processPayload, processSuffix,
                    null);
              } catch (IOException e) {
                log.error("Worker: Failed to enqueue results processing", e);
                throw new RuntimeException(e);
              }
              return "SUCCEEDED";

            } else {
              String errorMsg = error != null ? error.getMessage() : "Unknown Vertex error";
              log.error("Worker: Vertex Batch job {} failed! State: {}, Error: {}", batchJobId, state, errorMsg);

              String fallbackPayload = String.format(
                  "{\"analysisId\":\"%s\",\"gcsUri\":\"FAILED_%s\"}",
                  analysisId, errorMsg.replaceAll("[^A-Za-z0-9]", "_"));
              String fallbackSuffix = String.format("%s_PROCESS_RESULTS_FAIL", analysisId);

              try {
                cloudTasksService.enqueueTask("/api/v2/worker/process-batch-results", fallbackPayload, fallbackSuffix,
                    null);
              } catch (IOException e) {
                log.error("Worker: Failed to enqueue terminal fallback results processing", e);
                throw new RuntimeException(e);
              }
              return "FAILED";
            }
          }
        })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .flatMap(
            status -> Mono.just(ResponseEntity.ok("Status check handled: " + status)))
        .onErrorResume(
            e -> {
              log.error("Worker: Critical error polling status checker!", e);
              return Mono.just(ResponseEntity.internalServerError().body("Polling retry forced: " + e.getMessage()));
            });
  }

  private int getBackoffDelay(int attempt) {
    if (attempt <= 4)
      return 600; // Minute 0-10: check every 10 minutes (first attempts)
    return 180; // After 20 mins check every 3 mins
  }

  @PostMapping("/process-batch-results")
  public Mono<ResponseEntity<String>> processBatchResults(@RequestBody ProcessRequest request) {
    log.info("Worker: Received process-batch-results request for analysisId: {}, output GCS: {}",
        request.getAnalysisId(), request.getGcsUri());

    return analysisRequestRepository
        .findById(request.getAnalysisId())
        .map(AnalysisRequestEntity::getAnalysisStatus)
        .defaultIfEmpty("DELETED")
        .flatMap(
            status -> {
              if ("DELETED".equals(status)) {
                log.info("Worker: Skipping results processing — parent status is DELETED");
                return Mono.just(ResponseEntity.ok("Skipped: parent DELETED"));
              }
              return runProcessBatchResults(request);
            });
  }

  private Mono<ResponseEntity<String>> runProcessBatchResults(ProcessRequest request) {
    return Mono.fromRunnable(
        () -> {
          try {
            featureAnalysisService.processResultsAndSave(request.getAnalysisId(), request.getGcsUri());
          } catch (Exception e) {
            log.error("Worker: Critical failure processing consolidated batch results!", e);
            throw new RuntimeException(e);
          }
        })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .then(Mono.just(ResponseEntity.ok("Batch prediction results successfully processed")));
  }

  @PostMapping("/check-phase1-batch-status")
  public Mono<ResponseEntity<String>> checkPhase1BatchStatus(@RequestBody PollRequest request) {
    log.info("Worker: Polling check-phase1-batch-status for analysisId: {}, batchJobId: {}, attempt: {}",
        request.getAnalysisId(), request.getBatchJobId(), request.getAttemptCount());
    return analysisRequestRepository
        .findById(request.getAnalysisId())
        .map(AnalysisRequestEntity::getAnalysisStatus)
        .defaultIfEmpty("DELETED")
        .flatMap(
            status -> {
              if ("CANCELLED".equals(status) || "DELETED".equals(status)) {
                log.info("Worker: Skipping Phase 1 batch polling check — parent status is {}", status);
                return Mono.just(ResponseEntity.ok("Skipped: parent " + status));
              }
              return runCheckPhase1BatchStatus(request);
            });
  }
  private Mono<ResponseEntity<String>> runCheckPhase1BatchStatus(PollRequest request) {
    String analysisId = request.getAnalysisId();
    String batchJobId = request.getBatchJobId();
    int attempts = request.getAttemptCount();
    return Mono.fromCallable(
        () -> {
          String regionEndpoint = location + "-aiplatform.googleapis.com:443";
          JobServiceSettings settings = JobServiceSettings.newBuilder()
              .setEndpoint(regionEndpoint)
              .build();
          try (com.google.cloud.aiplatform.v1.JobServiceClient client = com.google.cloud.aiplatform.v1.JobServiceClient
              .create(settings)) {
            com.google.cloud.aiplatform.v1.BatchPredictionJob job = client.getBatchPredictionJob(batchJobId);
            com.google.cloud.aiplatform.v1.JobState state = job.getState();
            com.google.rpc.Status error = job.getError();
            log.info("Worker: Phase 1 Batch Prediction Job {} for analysisId {} is in state: {}",
                batchJobId, analysisId, state);
            if (state == com.google.cloud.aiplatform.v1.JobState.JOB_STATE_PENDING
                || state == com.google.cloud.aiplatform.v1.JobState.JOB_STATE_QUEUED
                || state == com.google.cloud.aiplatform.v1.JobState.JOB_STATE_RUNNING) {
              int nextAttempt = attempts + 1;
              int delay = getBackoffDelay(nextAttempt);
              String nextPayload = String.format(
                  "{\"analysisId\":\"%s\",\"batchJobId\":\"%s\",\"attemptCount\":%d}",
                  analysisId, batchJobId, nextAttempt);
              String nextSuffix = String.format("%s_P1_POLL_attempt_%d", analysisId, nextAttempt);
              log.info("Worker: Phase 1 Job still in-flight. Scheduling next check in {}s. Suffix: {}", delay, nextSuffix);
              try {
                cloudTasksService.enqueueTask("/api/v2/worker/check-phase1-batch-status", nextPayload, nextSuffix, delay);
              } catch (IOException e) {
                log.error("Worker: Failed to re-enqueue Phase 1 status check", e);
                throw new RuntimeException(e);
              }
              return "RUNNING";
            } else if (state == com.google.cloud.aiplatform.v1.JobState.JOB_STATE_SUCCEEDED) {
              String outputGcsUri = job.getOutputInfo().getGcsOutputDirectory();
              log.info("Worker: Phase 1 Batch Job Succeeded! Stage output staged at: {}. Enqueuing results parser.",
                  outputGcsUri);
              String processPayload = String.format(
                  "{\"analysisId\":\"%s\",\"gcsUri\":\"%s\"}",
                  analysisId, outputGcsUri);
              String processSuffix = String.format("%s_P1_PROCESS_RESULTS", analysisId);
              try {
                cloudTasksService.enqueueTask("/api/v2/worker/process-phase1-batch-results", processPayload, processSuffix, null);
              } catch (IOException e) {
                log.error("Worker: Failed to enqueue Phase 1 results processing", e);
                throw new RuntimeException(e);
              }
              return "SUCCEEDED";
            } else {
              String errorMsg = error != null ? error.getMessage() : "Unknown Vertex error";
              log.error("Worker: Vertex Phase 1 Batch job {} failed! State: {}, Error: {}", batchJobId, state, errorMsg);
              String fallbackPayload = String.format(
                  "{\"analysisId\":\"%s\",\"gcsUri\":\"FAILED_%s\"}",
                  analysisId, errorMsg.replaceAll("[^A-Za-z0-9]", "_"));
              String fallbackSuffix = String.format("%s_P1_PROCESS_RESULTS_FAIL", analysisId);
              try {
                cloudTasksService.enqueueTask("/api/v2/worker/process-phase1-batch-results", fallbackPayload, fallbackSuffix, null);
              } catch (IOException e) {
                log.error("Worker: Failed to enqueue Phase 1 terminal fallback results processing", e);
                throw new RuntimeException(e);
              }
              return "FAILED";
            }
          }
        })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .flatMap(
            status -> Mono.just(ResponseEntity.ok("Phase 1 status check handled: " + status)))
        .onErrorResume(
            e -> {
              log.error("Worker: Critical error polling Phase 1 status checker!", e);
              return Mono.just(ResponseEntity.internalServerError().body("Phase 1 Polling retry forced: " + e.getMessage()));
            });
  }
  @PostMapping("/process-phase1-batch-results")
  public Mono<ResponseEntity<String>> processPhase1BatchResults(@RequestBody ProcessRequest request) {
    log.info("Worker: Received process-phase1-batch-results request for analysisId: {}, output GCS: {}",
        request.getAnalysisId(), request.getGcsUri());
    return analysisRequestRepository
        .findById(request.getAnalysisId())
        .map(AnalysisRequestEntity::getAnalysisStatus)
        .defaultIfEmpty("DELETED")
        .flatMap(
            status -> {
              if ("DELETED".equals(status)) {
                log.info("Worker: Skipping Phase 1 results processing — parent status is DELETED");
                return Mono.just(ResponseEntity.ok("Skipped: parent DELETED"));
              }
              return runProcessPhase1BatchResults(request);
            });
  }
  private Mono<ResponseEntity<String>> runProcessPhase1BatchResults(ProcessRequest request) {
    return Mono.fromRunnable(
        () -> {
          try {
            featureAnalysisService.processPhase1ResultsAndSave(request.getAnalysisId(), request.getGcsUri());
          } catch (Exception e) {
            log.error("Worker: Critical failure processing consolidated Phase 1 batch results!", e);
            throw new RuntimeException(e);
          }
        })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .then(Mono.just(ResponseEntity.ok("Phase 1 batch prediction results successfully processed")));
  }

  @Data
  public static class PollRequest {
    private String analysisId;
    private String batchJobId;
    private int attemptCount;
  }

  @Data
  public static class ProcessRequest {
    private String analysisId;
    private String gcsUri;
  }
}
