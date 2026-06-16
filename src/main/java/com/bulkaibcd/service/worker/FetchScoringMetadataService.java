package com.bulkaibcd.service.worker;

import com.bulkaibcd.model.TaskRequest;
import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.enums.AnalysisType;
import com.bulkaibcd.enums.ScoringDimension;
import com.bulkaibcd.enums.SourceType;
import com.bulkaibcd.mapper.EntityMapper;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.client.GeminiClient;
import com.bulkaibcd.service.UploadUrlService;
import com.bulkaibcd.service.analysis.ApiService;
import com.google.cloud.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service responsible for executing Gemini prompts to evaluate the ABCD scoring rubric (A_ATTRACT,
 * B_BRAND, C_CONNECT, D_DIRECT) and generating the ASSET_NAME.
 *
 * <p>Handles idempotency checks, retry attempts, integer score parsing, completion verification,
 * and temporary GCS source cleanup upon landing all scores.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FetchScoringMetadataService
    implements ApiService<TaskRequest, ResponseEntity<String>> {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoMetadataRepository videoMetadataRepository;
  private final VideoInputRepository videoInputRepository;
  private final GeminiClient geminiClient;
  private final ObjectProvider<UploadUrlService> uploadUrlServiceProvider;

  private static final int MAX_FETCH_ATTEMPTS = 3;

  private static final Map<String, String> PROMPTS =
      Map.of(
          ScoringDimension.A_ATTRACT.name(),
          "Watch the video as if you are a viewer seeing it for the first time. On a scale of"
              + " 0 to 100, how attention-grabbing are the first three seconds? Consider"
              + " visual hook strength, pacing, and emotional pull. Respond with ONLY an"
              + " integer between 0 and 100, no explanation.",
          ScoringDimension.B_BRAND.name(),
          "On a scale of 0 to 100, how clearly and consistently is the brand identity"
              + " presented in this video (logo visibility, brand name mentions, brand-colour"
              + " consistency)? Respond with ONLY an integer between 0 and 100, no"
              + " explanation.",
          ScoringDimension.C_CONNECT.name(),
          "On a scale of 0 to 100, how emotionally engaging is this video? Consider"
              + " storytelling, relatability, memorable moments, and audience empathy."
              + " Respond with ONLY an integer between 0 and 100, no explanation.",
          ScoringDimension.D_DIRECT.name(),
          "On a scale of 0 to 100, how strong and clear is the call-to-action in this video?"
              + " Consider whether the viewer knows exactly what to do next and how"
              + " prominently that instruction is conveyed. Respond with ONLY an integer"
              + " between 0 and 100, no explanation.",
          ScoringDimension.ASSET_NAME.name(),
          "Create a short descriptive title for this video asset (max 8 words). Respond with"
              + " ONLY the title.");

  @Override
  public Mono<ResponseEntity<String>> execute(TaskRequest request) {
    int executionCount = request.getExecutionCount() != null ? request.getExecutionCount() : 0;

    log.info(
        "FetchScoringMetadataService: Polling fetch-metadata task for analysisId: {}, videoId: {},"
            + " promptType: {}. Execution: {}",
        request.getAnalysisId(),
        request.getVideoId(),
        request.getPromptType(),
        executionCount);

    String prompt = PROMPTS.get(request.getPromptType());
    if (prompt == null) {
      log.error(
          "FetchScoringMetadataService: Unknown prompt type received: {}", request.getPromptType());
      return Mono.just(ResponseEntity.badRequest().body("Unknown prompt type"));
    }

    return analysisRequestRepository
        .findById(request.getAnalysisId())
        .map(AnalysisRequestEntity::getAnalysisStatus)
        .defaultIfEmpty(AnalysisStatus.DELETED.name())
        .flatMap(
            status -> {
              if (AnalysisStatus.CANCELLED.name().equals(status)
                  || AnalysisStatus.DELETED.name().equals(status)) {
                log.info(
                    "FetchScoringMetadataService: Skipping task for {}/{}/{} — parent status is {}",
                    request.getAnalysisId(),
                    request.getVideoId(),
                    request.getPromptType(),
                    status);
                return Mono.just(ResponseEntity.ok("Skipped: parent " + status));
              }
              return runFetchMetadata(request, prompt, executionCount);
            });
  }

  private Mono<ResponseEntity<String>> runFetchMetadata(
      TaskRequest request, String prompt, int executionCount) {
    String metadataId = request.getAnalysisId() + "_" + request.getVideoId();
    String promptType = request.getPromptType();

    return videoMetadataRepository
        .findById(metadataId)
        .defaultIfEmpty(
            EntityMapper.toVideoMetadataEntity(request.getAnalysisId(), request.getVideoId()))
        .flatMap(
            metadata -> {
              if (isScoreAlreadySet(metadata, promptType)) {
                log.info(
                    "FetchScoringMetadataService: Prompt {} for video {}/{} is already completed."
                        + " Skipping.",
                    promptType,
                    request.getAnalysisId(),
                    request.getVideoId());
                return Mono.just(ResponseEntity.ok("Already processed"));
              }

              log.info(
                  "FetchScoringMetadataService: Processing fetch-metadata request for {}/{}/{}."
                      + " Attempt (Execution Count): {}",
                  request.getAnalysisId(),
                  request.getVideoId(),
                  promptType,
                  executionCount);

              return Mono.fromCallable(
                      () ->
                          geminiClient.callGemini(request.getVideoUri(), prompt, Optional.empty()))
                  .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                  .flatMap(
                      result -> {
                        log.info(
                            "FetchScoringMetadataService: Gemini response for {}/{}/{}: {}",
                            request.getAnalysisId(),
                            request.getVideoId(),
                            promptType,
                            result);

                        return videoMetadataRepository
                            .findById(metadataId)
                            .defaultIfEmpty(metadata)
                            .flatMap(
                                latestMetadata -> {
                                  updateMetadataField(latestMetadata, promptType, result);

                                  return isCompleteReactive(latestMetadata, request.getAnalysisId())
                                      .flatMap(
                                          completed -> {
                                            boolean justCompleted = false;
                                            if (completed) {
                                              log.info(
                                                  "FetchScoringMetadataService: Video {}/{} is now"
                                                      + " COMPLETED.",
                                                  request.getAnalysisId(),
                                                  request.getVideoId());
                                              latestMetadata.setStatus(
                                                  AnalysisStatus.COMPLETED.name());
                                              justCompleted = true;
                                            }
                                            Mono<Void> postSave =
                                                videoMetadataRepository
                                                    .save(latestMetadata)
                                                    .then(
                                                        checkAnalysisCompletion(
                                                            request.getAnalysisId()));
                                            if (justCompleted) {
                                              log.info(
                                                  "FetchScoringMetadataService: Cleaning up source"
                                                      + " for {}/{}",
                                                  request.getAnalysisId(),
                                                  request.getVideoId());
                                              postSave =
                                                  postSave.then(
                                                      deleteUploadedSource(
                                                          request.getAnalysisId(),
                                                          request.getVideoId()));
                                            }
                                            return postSave;
                                          });
                                });
                      })
                  .then(Mono.just(ResponseEntity.ok("Metadata updated")))
                  .onErrorResume(
                      e -> {
                        log.error(
                            "FetchScoringMetadataService: Gemini processing failed for {}/{}/{} on"
                                + " execution {}.",
                            request.getAnalysisId(),
                            request.getVideoId(),
                            promptType,
                            executionCount,
                            e);
                        return recordFailureOrRetryReactive(request, e, executionCount);
                      });
            });
  }

  private boolean isScoreAlreadySet(VideoMetadataEntity m, String type) {
    if (type == null) return false;
    try {
      ScoringDimension sd = ScoringDimension.valueOf(type.toUpperCase());
      return switch (sd) {
        case A_ATTRACT -> m.getAScore() != null;
        case B_BRAND -> m.getBScore() != null;
        case C_CONNECT -> m.getCScore() != null;
        case D_DIRECT -> m.getDScore() != null;
        case ASSET_NAME -> m.getAssetName() != null && !m.getAssetName().isEmpty();
      };
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private Mono<ResponseEntity<String>> recordFailureOrRetryReactive(
      TaskRequest request, Throwable err, int executionCount) {
    String id = request.getAnalysisId() + "_" + request.getVideoId();
    String promptType = request.getPromptType();

    return videoMetadataRepository
        .findById(id)
        .defaultIfEmpty(
            EntityMapper.toVideoMetadataEntity(request.getAnalysisId(), request.getVideoId()))
        .flatMap(
            metadata -> {
              String msg = err.getMessage();
              if (msg != null && msg.length() > 300) msg = msg.substring(0, 300);

              if (executionCount < (MAX_FETCH_ATTEMPTS - 1)) {
                int humanAttemptNumber = executionCount + 1;
                log.info(
                    "FetchScoringMetadataService: Failure logged on attempt {}/{} for {}/{}/{}."
                        + " Scheduling retry. Error: {}",
                    humanAttemptNumber,
                    MAX_FETCH_ATTEMPTS,
                    request.getAnalysisId(),
                    request.getVideoId(),
                    promptType,
                    msg);

                return videoMetadataRepository
                    .save(metadata)
                    .then(Mono.just(ResponseEntity.internalServerError().body("retrying: " + msg)));
              }

              log.warn(
                  "FetchScoringMetadataService: Terminal failure for {}/{}/{} reached after max"
                      + " attempts ({}). Saving placeholder.",
                  request.getAnalysisId(),
                  request.getVideoId(),
                  promptType,
                  MAX_FETCH_ATTEMPTS);

              fillTerminalFailure(metadata, promptType);
              metadata.setErrorMessage(msg);

              return isCompleteReactive(metadata, request.getAnalysisId())
                  .flatMap(
                      completed -> {
                        if (completed) {
                          metadata.setStatus(AnalysisStatus.COMPLETED.name());
                        } else {
                          metadata.setStatus(AnalysisStatus.FAILED.name());
                        }
                        return videoMetadataRepository
                            .save(metadata)
                            .then(checkAnalysisCompletion(request.getAnalysisId()))
                            .then(Mono.just(ResponseEntity.ok("Recorded terminal failure")));
                      });
            });
  }

  private void fillTerminalFailure(VideoMetadataEntity m, String promptType) {
    if (promptType == null) return;
    try {
      ScoringDimension sd = ScoringDimension.valueOf(promptType.toUpperCase());
      switch (sd) {
        case A_ATTRACT -> {
          if (m.getAScore() == null) m.setAScore(0);
        }
        case B_BRAND -> {
          if (m.getBScore() == null) m.setBScore(0);
        }
        case C_CONNECT -> {
          if (m.getCScore() == null) m.setCScore(0);
        }
        case D_DIRECT -> {
          if (m.getDScore() == null) m.setDScore(0);
        }
        case ASSET_NAME -> {
          if (m.getAssetName() == null) m.setAssetName("");
        }
      }
    } catch (IllegalArgumentException e) {
      log.warn("FetchScoringMetadataService: Unknown ScoringDimension: {}", promptType);
    }
  }

  private void updateMetadataField(VideoMetadataEntity metadata, String type, String value) {
    log.info(
        "FetchScoringMetadataService: Updating field {} for video {} with value: {}",
        type,
        metadata.getVideoId(),
        value);
    if (type == null) return;
    try {
      ScoringDimension sd = ScoringDimension.valueOf(type.toUpperCase());
      switch (sd) {
        case A_ATTRACT -> metadata.setAScore(parseScore(value));
        case B_BRAND -> metadata.setBScore(parseScore(value));
        case C_CONNECT -> metadata.setCScore(parseScore(value));
        case D_DIRECT -> metadata.setDScore(parseScore(value));
        case ASSET_NAME -> metadata.setAssetName(value == null ? "" : value.trim());
      }
    } catch (IllegalArgumentException e) {
      log.warn("FetchScoringMetadataService: Unknown ScoringDimension: {}", type);
    }
  }

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

  private Mono<Void> deleteUploadedSource(String analysisId, String videoId) {
    return videoInputRepository
        .findById(analysisId + "_" + videoId)
        .flatMap(
            input -> {
              if (SourceType.FILE.name().equalsIgnoreCase(input.getSourceType())
                  && input.getGcsObjectId() != null) {
                UploadUrlService svc = uploadUrlServiceProvider.getIfAvailable();
                if (svc != null) svc.delete(input.getGcsObjectId());
              }
              return Mono.empty();
            })
        .then();
  }

  private Mono<Boolean> isCompleteReactive(VideoMetadataEntity m, String analysisId) {
    if (m.getAssetName() == null) {
      return Mono.just(false);
    }

    boolean abcdAllSet =
        m.getAScore() != null
            && m.getBScore() != null
            && m.getCScore() != null
            && m.getDScore() != null;
    if (abcdAllSet) {
      return Mono.just(true);
    }

    boolean abOnly = m.getAScore() != null && m.getBScore() != null;
    if (!abOnly) {
      return Mono.just(false);
    }

    return analysisRequestRepository
        .findById(analysisId)
        .map(
            parent ->
                parent != null
                    && AnalysisType.LIGHT.name().equalsIgnoreCase(parent.getAnalysisType()))
        .defaultIfEmpty(false);
  }

  private Mono<Void> checkAnalysisCompletion(String analysisId) {
    return videoInputRepository
        .findByAnalysisId(analysisId)
        .collectList()
        .flatMap(
            inputs -> {
              if (inputs.isEmpty()) {
                return Mono.empty();
              }

              return Flux.fromIterable(inputs)
                  .flatMap(
                      input -> {
                        String metadataId = analysisId + "_" + input.getVideoId();
                        return videoMetadataRepository
                            .findById(metadataId)
                            .defaultIfEmpty(
                                EntityMapper.toVideoMetadataEntity(analysisId, input.getVideoId()));
                      },
                      8)
                  .all(
                      v ->
                          AnalysisStatus.COMPLETED.name().equals(v.getStatus())
                              || AnalysisStatus.FAILED.name().equals(v.getStatus()))
                  .flatMap(
                      allDone -> {
                        if (allDone) {
                          log.info(
                              "FetchScoringMetadataService: Strongly consistent check: All {}"
                                  + " videos for analysis {} are COMPLETED/FAILED. Marking parent"
                                  + " as COMPLETED.",
                              inputs.size(),
                              analysisId);
                          return analysisRequestRepository
                              .findById(analysisId)
                              .flatMap(
                                  request -> {
                                    request.setAnalysisStatus(AnalysisStatus.COMPLETED.name());
                                    request.setUpdatedAt(Timestamp.now());
                                    return analysisRequestRepository.save(request);
                                  })
                              .then();
                        }
                        return Mono.empty();
                      });
            });
  }
}
