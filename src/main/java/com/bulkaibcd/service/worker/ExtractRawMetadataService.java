package com.bulkaibcd.service.worker;

import com.bulkaibcd.config.AnalysisConstants;
import com.bulkaibcd.model.TaskRequest;
import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.enums.RawMetadataType;
import com.bulkaibcd.mapper.EntityMapper;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.client.GeminiClient;
import com.bulkaibcd.service.analysis.ApiService;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service responsible for executing online Cloud Tasks webhooks to extract raw structural metadata
 * (e.g., BRAND, PRODUCT, FACES DETECTED) from video assets using Gemini.
 *
 * <p>Handles idempotency checks, Firestore transactional updates, retry attempt management,
 * terminal failure defaults, and verification of the Phase 1 completion gate to trigger Phase 2.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ExtractRawMetadataService implements ApiService<TaskRequest, ResponseEntity<String>> {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoMetadataRepository videoMetadataRepository;
  private final VideoInputRepository videoInputRepository;
  private final GeminiClient geminiClient;
  private final Firestore firestore;
  private final CloudTasksQueueClient cloudTasksQueueClient;

  private static final int MAX_FETCH_ATTEMPTS = 3;

  @Override
  public Mono<ResponseEntity<String>> execute(TaskRequest request) {
    int executionCount = request.getExecutionCount() != null ? request.getExecutionCount() : 0;

    log.info(
        "ExtractRawMetadataService: Polling extract-raw-metadata task for analysisId: {}, videoId:"
            + " {}, promptType: {}. Execution: {}",
        request.getAnalysisId(),
        request.getVideoId(),
        request.getPromptType(),
        executionCount);

    String prompt = AnalysisConstants.RAW_PROMPT_MAP.get(request.getPromptType());
    if (prompt == null) {
      log.error(
          "ExtractRawMetadataService: Unknown raw prompt type received: {}",
          request.getPromptType());
      return Mono.just(ResponseEntity.badRequest().body("Unknown raw prompt type"));
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
                    "ExtractRawMetadataService: Skipping extraction task for {}/{}/{} — parent"
                        + " status is {}",
                    request.getAnalysisId(),
                    request.getVideoId(),
                    request.getPromptType(),
                    status);
                return Mono.just(ResponseEntity.ok("Skipped: parent " + status));
              }
              return runExtractRawMetadata(request, prompt, executionCount);
            });
  }

  private Mono<ResponseEntity<String>> runExtractRawMetadata(
      TaskRequest request, String prompt, int executionCount) {
    String metadataId = request.getAnalysisId() + "_" + request.getVideoId();
    String promptType = request.getPromptType();

    return videoMetadataRepository
        .findById(metadataId)
        .defaultIfEmpty(
            EntityMapper.toVideoMetadataEntity(request.getAnalysisId(), request.getVideoId()))
        .flatMap(
            metadata -> {
              if (isRawMetadataFieldPopulated(metadata, promptType)) {
                log.info(
                    "ExtractRawMetadataService: Raw metadata {} for video {}/{} is already"
                        + " completed. Skipping.",
                    promptType,
                    request.getAnalysisId(),
                    request.getVideoId());
                return Mono.just(ResponseEntity.ok("Already processed"));
              }

              log.info(
                  "ExtractRawMetadataService: Processing extract-raw-metadata request for {}/{}/{}."
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
                            "ExtractRawMetadataService: Gemini response raw for {}/{}/{}: {}",
                            request.getAnalysisId(),
                            request.getVideoId(),
                            promptType,
                            result);

                        DocumentReference docRef =
                            firestore.collection("video_metadata").document(metadataId);

                        return Mono.fromCallable(
                                () ->
                                    firestore
                                        .runTransaction(
                                            transaction -> {
                                              DocumentSnapshot snapshot =
                                                  transaction.get(docRef).get();
                                              VideoMetadataEntity latestMetadata;
                                              if (snapshot.exists()) {
                                                latestMetadata =
                                                    snapshot.toObject(VideoMetadataEntity.class);
                                              } else {
                                                latestMetadata = metadata;
                                              }
                                              updateRawMetadataField(
                                                  latestMetadata, promptType, result);

                                              boolean rawPhaseDone =
                                                  isMetadataPhaseComplete(latestMetadata);
                                              if (rawPhaseDone) {
                                                log.info(
                                                    "ExtractRawMetadataService: Video {}/{} has"
                                                        + " finished Phase 1 (14 metadata fields"
                                                        + " fanned-in). Promoting status to"
                                                        + " METADATA_EXTRACTED.",
                                                    request.getAnalysisId(),
                                                    request.getVideoId());
                                                latestMetadata.setStatus(
                                                    AnalysisStatus.METADATA_EXTRACTED.name());
                                              }

                                              transaction.set(docRef, latestMetadata);
                                              return rawPhaseDone;
                                            })
                                        .get())
                            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                            .flatMap(
                                rawPhaseDone -> {
                                  if (rawPhaseDone) {
                                    return checkMetadataPhaseBatchCompletion(
                                        request.getAnalysisId());
                                  }
                                  return Mono.empty();
                                });
                      })
                  .then(Mono.just(ResponseEntity.ok("Raw metadata updated")))
                  .onErrorResume(
                      e -> {
                        log.error(
                            "ExtractRawMetadataService: Gemini raw extraction failed for {}/{}/{}"
                                + " on execution {}.",
                            request.getAnalysisId(),
                            request.getVideoId(),
                            promptType,
                            executionCount,
                            e);
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
            EntityMapper.toVideoMetadataEntity(request.getAnalysisId(), request.getVideoId()))
        .flatMap(
            metadata -> {
              String rawMsg = err.getMessage();
              final String msg =
                  (rawMsg != null && rawMsg.length() > 300) ? rawMsg.substring(0, 300) : rawMsg;

              if (executionCount < (MAX_FETCH_ATTEMPTS - 1)) {
                int humanAttemptNumber = executionCount + 1;
                log.info(
                    "ExtractRawMetadataService: Raw extraction failure on attempt {}/{} for"
                        + " {}/{}/{}. Scheduling GTasks retry. Error: {}",
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
                  "ExtractRawMetadataService: Terminal raw extraction failure for {}/{}/{} reached"
                      + " after max attempts ({}). Saving empty placeholder.",
                  request.getAnalysisId(),
                  request.getVideoId(),
                  promptType,
                  MAX_FETCH_ATTEMPTS);

              DocumentReference docRef = firestore.collection("video_metadata").document(id);

              return Mono.fromCallable(
                      () ->
                          firestore
                              .runTransaction(
                                  transaction -> {
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
                                      log.info(
                                          "ExtractRawMetadataService: Video {}/{} has finished"
                                              + " Phase 1 on terminal fallback. Status ->"
                                              + " METADATA_EXTRACTED.",
                                          request.getAnalysisId(),
                                          request.getVideoId());
                                      latestMetadata.setStatus(
                                          AnalysisStatus.METADATA_EXTRACTED.name());
                                    } else {
                                      latestMetadata.setStatus(AnalysisStatus.FAILED.name());
                                    }

                                    transaction.set(docRef, latestMetadata);
                                    return rawPhaseDone;
                                  })
                              .get())
                  .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                  .flatMap(
                      rawPhaseDone -> {
                        if (rawPhaseDone) {
                          return checkMetadataPhaseBatchCompletion(request.getAnalysisId());
                        }
                        return Mono.empty();
                      })
                  .then(Mono.just(ResponseEntity.ok("Recorded terminal extraction failure")));
            });
  }

  private boolean isRawMetadataFieldPopulated(VideoMetadataEntity m, String type) {
    if (type == null) return false;
    try {
      RawMetadataType rmt = RawMetadataType.valueOf(type.toUpperCase());
      return switch (rmt) {
        case BRAND -> m.getBrand() != null;
        case PRODUCT -> m.getProduct() != null;
        case LANGUAGE -> m.getVideoLanguage() != null;
        case VERTICAL -> m.getVertical() != null;
        case ASSET_NAME -> m.getAssetName() != null && !m.getAssetName().isEmpty();
        case SPEECH_TRANSCRIPTION -> m.getSpeech() != null;
        case TEXT_DETECTION -> m.getText() != null;
        case SHOT_CHANGE_DETECTION -> m.getShot() != null;
        case LOGO_RECOGNITION -> m.getLogo() != null;
        case OBJECT_TRACKING -> m.getObjects() != null;
        case FACE_DETECTION -> m.getFace() != null;
        case PERSON_DETECTION -> m.getPerson() != null;
        case LABEL_DETECTION -> m.getLabelName() != null;
        case EXPLICIT_CONTENT_DETECTION -> m.getExplicit() != null;
      };
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private void updateRawMetadataField(VideoMetadataEntity m, String type, String val) {
    if (type == null) return;
    String cleaned = stripQuotes(val);
    try {
      RawMetadataType rmt = RawMetadataType.valueOf(type.toUpperCase());
      switch (rmt) {
        case BRAND -> m.setBrand(cleaned);
        case PRODUCT -> m.setProduct(cleaned);
        case LANGUAGE -> m.setVideoLanguage(cleaned);
        case VERTICAL -> m.setVertical(cleaned);
        case ASSET_NAME -> m.setAssetName(cleaned);
        case SPEECH_TRANSCRIPTION -> m.setSpeech(cleaned);
        case TEXT_DETECTION -> m.setText(cleaned);
        case SHOT_CHANGE_DETECTION -> m.setShot(cleaned);
        case LOGO_RECOGNITION -> m.setLogo(cleaned);
        case OBJECT_TRACKING -> m.setObjects(cleaned);
        case FACE_DETECTION -> m.setFace(cleaned);
        case PERSON_DETECTION -> m.setPerson(cleaned);
        case LABEL_DETECTION -> m.setLabelName(cleaned);
        case EXPLICIT_CONTENT_DETECTION -> m.setExplicit(cleaned);
      }
    } catch (IllegalArgumentException e) {
      log.warn("ExtractRawMetadataService: Unknown RawMetadataType: {}", type);
    }
  }

  private boolean isMetadataPhaseComplete(VideoMetadataEntity m) {
    return m.getBrand() != null
        && m.getProduct() != null
        && m.getVideoLanguage() != null
        && m.getVertical() != null
        && m.getAssetName() != null
        && !m.getAssetName().isEmpty()
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
    if (promptType == null) return;
    try {
      RawMetadataType rmt = RawMetadataType.valueOf(promptType.toUpperCase());
      switch (rmt) {
        case BRAND -> {
          if (m.getBrand() == null) m.setBrand("");
        }
        case PRODUCT -> {
          if (m.getProduct() == null) m.setProduct("");
        }
        case LANGUAGE -> {
          if (m.getVideoLanguage() == null) m.setVideoLanguage("zxx");
        }
        case VERTICAL -> {
          if (m.getVertical() == null) m.setVertical("");
        }
        case ASSET_NAME -> {
          if (m.getAssetName() == null) m.setAssetName("");
        }
        case SPEECH_TRANSCRIPTION -> {
          if (m.getSpeech() == null) m.setSpeech("[]");
        }
        case TEXT_DETECTION -> {
          if (m.getText() == null) m.setText("[]");
        }
        case SHOT_CHANGE_DETECTION -> {
          if (m.getShot() == null) m.setShot("[]");
        }
        case LOGO_RECOGNITION -> {
          if (m.getLogo() == null) m.setLogo("[]");
        }
        case OBJECT_TRACKING -> {
          if (m.getObjects() == null) m.setObjects("[]");
        }
        case FACE_DETECTION -> {
          if (m.getFace() == null) m.setFace("[]");
        }
        case PERSON_DETECTION -> {
          if (m.getPerson() == null) m.setPerson("[]");
        }
        case LABEL_DETECTION -> {
          if (m.getLabelName() == null) m.setLabelName("[]");
        }
        case EXPLICIT_CONTENT_DETECTION -> {
          if (m.getExplicit() == null) m.setExplicit("[]");
        }
      }
    } catch (IllegalArgumentException e) {
      log.warn("ExtractRawMetadataService: Unknown RawMetadataType: {}", promptType);
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
    return videoInputRepository
        .findByAnalysisId(analysisId)
        .collectList()
        .flatMap(
            inputs -> {
              if (inputs.isEmpty()) return Mono.empty();

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
                          AnalysisStatus.METADATA_EXTRACTED.name().equals(v.getStatus())
                              || AnalysisStatus.COMPLETED.name().equals(v.getStatus())
                              || AnalysisStatus.FAILED.name().equals(v.getStatus()))
                  .flatMap(
                      allReady -> {
                        if (allReady) {
                          log.info(
                              "ExtractRawMetadataService: Dynamic Gate Verified: All {} videos in"
                                  + " analysis {} finished Phase 1 (Metadata Extraction)."
                                  + " Triggering Phase 2 (Feature Analysis).",
                              inputs.size(),
                              analysisId);

                          String taskSuffix = String.format("%s_START_PHASE2", analysisId);
                          String payload = String.format("{\"analysisId\":\"%s\"}", analysisId);

                          try {
                            cloudTasksQueueClient.enqueueTask(
                                "/api/v2/worker/start-phase2", payload, taskSuffix, null);
                            log.info(
                                "ExtractRawMetadataService: Phase 2 start task successfully"
                                    + " enqueued for run: {}",
                                analysisId);
                          } catch (IOException e) {
                            log.error(
                                "ExtractRawMetadataService: Failed to trigger Phase 2 for run: {}",
                                analysisId,
                                e);
                            return Mono.error(e);
                          }
                        }
                        return Mono.empty();
                      });
            });
  }
}
