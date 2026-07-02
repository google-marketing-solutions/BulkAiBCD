package com.bulkaibcd.service.batch;

import com.bulkaibcd.config.AnalysisConstants;
import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.enums.SourceType;
import com.bulkaibcd.enums.VideoFormat;
import com.bulkaibcd.mapper.EntityMapper;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.FeatureParameter;
import com.bulkaibcd.model.GuidelineRelevance;
import com.bulkaibcd.model.NotDetectedFeatureEntity;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.client.GcsClient;
import com.bulkaibcd.client.VertexBatchClient;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.FeatureConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.ReadChannel;
import com.google.cloud.Timestamp;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Clean, high-level workflow orchestrator for Vertex AI Batch Prediction jobs. */
@Service
@Slf4j
@RequiredArgsConstructor
public class BatchPredictionOrchestrator {

  private final GcsClient gcsStorage;
  private final VertexBatchClient vertexBatchJob;
  private final PromptBuilderService promptBuilder;
  private final DynamicScoringService dynamicScoring;
  private final FeatureConfigService featureConfigService;
  private final VideoMetadataRepository videoMetadataRepository;
  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoInputRepository videoInputRepository;
  private final CloudTasksQueueClient cloudTasksService;
  private final ObjectMapper objectMapper;

  @Value("${app.uploads-bucket}")
  private String uploadsBucket;

  @Value("${google.cloud.model.name:gemini-2.5-pro}")
  private String modelName;

  private static class ParsedFeature {
    boolean detected;
    String rationale;

    ParsedFeature(boolean detected, String rationale) {
      this.detected = detected;
      this.rationale = rationale;
    }
  }

  public String submitPhase1Job(String analysisId, List<VideoInputEntity> videos)
      throws IOException {
    log.info(
        "BatchPredictionOrchestrator: Starting Phase 1 consolidated batch prediction setup for {}"
            + " videos...",
        videos.size());
    List<VideoInputEntity> validVideos =
        videos.stream()
            .filter(v -> v.getErrorMessage() == null || v.getErrorMessage().isEmpty())
            .collect(Collectors.toList());

    if (validVideos.isEmpty()) {
      log.warn("BatchPredictionOrchestrator: No valid videos to process for Phase 1 batch run.");
      AnalysisRequestEntity parent = analysisRequestRepository.findById(analysisId).block();
      if (parent != null) {
        parent.setAnalysisStatus(AnalysisStatus.COMPLETED.name());
        parent.setUpdatedAt(Timestamp.now());
        analysisRequestRepository.save(parent).block();
      }
      return "NO_VALID_VIDEOS";
    }

    String objectPath = String.format("phase1_batches/%s/input.jsonl", analysisId);
    log.info(
        "BatchPredictionOrchestrator: Streaming Phase 1 input JSONL file directly to GCS:"
            + " gs://{}/{}",
        uploadsBucket,
        objectPath);

    try (WriteChannel channel =
        gcsStorage.createWriter(uploadsBucket, objectPath, "application/jsonl")) {
      for (VideoInputEntity v : validVideos) {
        String videoUri = resolveVideoUri(v);
        for (String metadataType : AnalysisConstants.METADATA_TYPES) {
          String prompt = AnalysisConstants.RAW_PROMPT_MAP.get(metadataType);
          if (prompt == null) continue;
          Map<String, String> instanceMeta =
              Map.of(
                  "video_uri", videoUri,
                  "video_id", v.getVideoId(),
                  "prompt_type", metadataType);
          String jsonlInstanceString;
          try {
            jsonlInstanceString = objectMapper.writeValueAsString(instanceMeta);
          } catch (IOException e) {
            jsonlInstanceString = "{}";
          }
          boolean isJson =
              !List.of("BRAND", "PRODUCT", "LANGUAGE", "VERTICAL", "ASSET_NAME")
                  .contains(metadataType);
          String row =
              promptBuilder.createPhase1RequestPayloadRow(
                  videoUri, prompt, jsonlInstanceString, isJson);
          if (!row.isEmpty()) {
            channel.write(ByteBuffer.wrap(row.getBytes(StandardCharsets.UTF_8)));
            channel.write(ByteBuffer.wrap("\n".getBytes(StandardCharsets.UTF_8)));
          }
        }
      }
    }

    String inputGcsUri = String.format("gs://%s/%s", uploadsBucket, objectPath);
    String modelResourceName = String.format("publishers/google/models/%s", modelName);
    String outputGcsPrefix =
        String.format("gs://%s/phase1_batches/%s/output/", uploadsBucket, analysisId);
    String jobDisplayName = String.format("bulkaibcd_phase1_%s", analysisId);

    return vertexBatchJob.createBatchPredictionJob(
        jobDisplayName, modelResourceName, inputGcsUri, outputGcsPrefix);
  }

  @SuppressWarnings("unchecked")
  public void processPhase1Results(String analysisId, String gcsOutputUri) throws Exception {
    log.info(
        "BatchPredictionOrchestrator: Starting Phase 1 fanned-in predictions results processing"
            + " under: {}",
        gcsOutputUri);
    String prefix = gcsOutputUri.replace("gs://", "");
    int slash = prefix.indexOf('/');
    if (slash < 0) throw new IllegalArgumentException("Bad GCS output prefix: " + gcsOutputUri);
    String bucketName = prefix.substring(0, slash);
    String outputPrefix = prefix.substring(slash + 1);

    Map<String, Map<String, String>> videoResults = new HashMap<>();
    var blobs = gcsStorage.listBlobs(bucketName, outputPrefix);
    for (Blob blob : blobs) {
      String name = blob.getName();
      if (!name.endsWith(".jsonl") || !name.contains("prediction")) continue;
      log.info(
          "BatchPredictionOrchestrator: Streaming Phase 1 output predictions blob line-by-line: {}",
          name);
      try (ReadChannel reader = blob.reader();
          BufferedReader lineReader =
              new BufferedReader(
                  new InputStreamReader(Channels.newInputStream(reader), StandardCharsets.UTF_8))) {
        String line;
        while ((line = lineReader.readLine()) != null) {
          if (line.trim().isEmpty()) continue;
          try {
            Map<String, Object> record =
                objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
            String instanceStr = (String) record.get("instance");
            Map<String, String> instance =
                objectMapper.readValue(instanceStr, new TypeReference<Map<String, String>>() {});
            String videoId = instance.get("video_id");
            String promptType = instance.get("prompt_type");
            Map<String, Object> response = (Map<String, Object>) record.get("response");
            String resultText = "";
            if (response != null) {
              List<Map<String, Object>> candidates =
                  (List<Map<String, Object>>) response.get("candidates");
              if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> candidate = candidates.get(0);
                Map<String, Object> contentPart = (Map<String, Object>) candidate.get("content");
                if (contentPart != null) {
                  List<Map<String, Object>> parts =
                      (List<Map<String, Object>>) contentPart.get("parts");
                  if (parts != null && !parts.isEmpty()) {
                    resultText = (String) parts.get(0).get("text");
                  }
                }
              }
            }
            if (resultText == null) resultText = "";
            videoResults.computeIfAbsent(videoId, k -> new HashMap<>()).put(promptType, resultText);
          } catch (Exception e) {
            log.warn("BatchPredictionOrchestrator: Failed to decode Phase 1 predictions row.", e);
          }
        }
      }
    }

    if (videoResults.isEmpty()) {
      if (outputPrefix.contains("FAILED_")) {
        log.warn(
            "BatchPredictionOrchestrator: Triggered Phase 1 results processor under failure"
                + " gateway.");
      } else {
        throw new IllegalStateException(
            "No Phase 1 predictions metadata parsed successfully for batch: " + analysisId);
      }
    }

    AnalysisRequestEntity parent = analysisRequestRepository.findById(analysisId).block();
    if (parent == null)
      throw new IllegalArgumentException("Analysis parent request not found: " + analysisId);

    log.info("BatchPredictionOrchestrator: Updating metadata in Firestore for all videos...");
    videoInputRepository
        .findByAnalysisId(analysisId)
        .flatMap(
            input -> {
              String videoId = input.getVideoId();
              String metadataId = analysisId + "_" + videoId;
              return videoMetadataRepository
                  .findById(metadataId)
                  .defaultIfEmpty(EntityMapper.toVideoMetadataEntity(analysisId, videoId))
                  .flatMap(
                      metadata -> {
                        Map<String, String> preds =
                            videoResults.getOrDefault(videoId, new HashMap<>());
                        for (String promptType : AnalysisConstants.METADATA_TYPES) {
                          String val = preds.get(promptType);
                          if (val != null && !val.isEmpty()) {
                            promptBuilder.updateRawMetadataField(metadata, promptType, val);
                          } else {
                            promptBuilder.fillTerminalRawFailure(metadata, promptType);
                          }
                        }
                        if (outputPrefix.contains("FAILED_")) {
                          metadata.setStatus(AnalysisStatus.FAILED.name());
                          metadata.setErrorMessage(outputPrefix.replace("FAILED_", ""));
                        } else {
                          metadata.setStatus(AnalysisStatus.METADATA_EXTRACTED.name());
                        }
                        return videoMetadataRepository
                            .save(metadata)
                            .onErrorResume(
                                err -> {
                                  log.warn(
                                      "BatchPredictionOrchestrator: Firestore save failed for video"
                                          + " {} (likely >1MB limit). Error: {}. Stripping heavy"
                                          + " annotations.",
                                      videoId,
                                      err.getMessage());
                                  metadata.setLabelName("[]");
                                  metadata.setObjects("[]");
                                  metadata.setPerson("[]");
                                  metadata.setFace("[]");
                                  metadata.setText("[]");
                                  metadata.setLogo("[]");
                                  return videoMetadataRepository
                                      .save(metadata)
                                      .onErrorResume(
                                          err2 -> {
                                            log.error(
                                                "BatchPredictionOrchestrator: Second Firestore save"
                                                    + " attempt also failed for video {}. Stripping"
                                                    + " all annotations.",
                                                videoId);
                                            metadata.setSpeech("[]");
                                            metadata.setShot("[]");
                                            metadata.setExplicit("[]");
                                            return videoMetadataRepository.save(metadata);
                                          });
                                });
                      });
            },
            8)
        .collectList()
        .block();

    log.info(
        "BatchPredictionOrchestrator: Successfully committed Phase 1 video results to Firestore.");
    gcsStorage.purgeGcsFolder(uploadsBucket, String.format("phase1_batches/%s/", analysisId));

    if (!outputPrefix.contains("FAILED_")) {
      log.info(
          "BatchPredictionOrchestrator: Phase 1 completed successfully. Enqueuing Phase 2 start"
              + " task.");
      String taskSuffix = String.format("%s_START_PHASE2", analysisId);
      String payload = String.format("{\"analysisId\":\"%s\"}", analysisId);
      try {
        cloudTasksService.enqueueTask("/api/v2/worker/start-phase2", payload, taskSuffix, null);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public String submitPhase2Job(
      String analysisId, List<VideoMetadataEntity> videos, String analysisType, String brandName)
      throws IOException {
    log.info(
        "BatchPredictionOrchestrator: Starting Phase 2 consolidated batch prediction setup for {}"
            + " videos...",
        videos.size());
    List<FeatureParameter> targetFeaturesLong = featureConfigService.getFeaturesByTypeAndFormat(analysisType, VideoFormat.LONG);
    List<FeatureParameter> targetFeaturesShort = featureConfigService.getFeaturesByTypeAndFormat(analysisType, VideoFormat.SHORT);
    if (targetFeaturesLong.isEmpty() && targetFeaturesShort.isEmpty()) {
      throw new IllegalArgumentException(
          "No features configured on resource mapper for analysisType: " + analysisType);
    }
    AnalysisRequestEntity parent = analysisRequestRepository.findById(analysisId).block();

    String objectPath = String.format("phase2_batches/%s/input.jsonl", analysisId);
    log.info(
        "BatchPredictionOrchestrator: Streaming unified input JSONL file directly to GCS:"
            + " gs://{}/{}",
        uploadsBucket,
        objectPath);

    try (WriteChannel channel =
        gcsStorage.createWriter(uploadsBucket, objectPath, "application/jsonl")) {
      for (VideoMetadataEntity video : videos) {
        List<FeatureParameter> targetFeatures = VideoFormat.SHORT.name().equalsIgnoreCase(video.getFormat()) ? targetFeaturesShort : targetFeaturesLong;
        if ("custom".equals(analysisType) && parent != null) {
          List<String> customFeatures = VideoFormat.SHORT.name().equalsIgnoreCase(video.getFormat()) ? parent.getCustomFeaturesShort() : parent.getCustomFeaturesLong();
          if (customFeatures == null) customFeatures = new ArrayList<>();
          List<String> finalCustomFeatures = customFeatures;
          targetFeatures = targetFeatures.stream()
              .filter(f -> finalCustomFeatures.contains(f.getName()))
              .collect(Collectors.toList());
        }
        String metadataSummary = promptBuilder.createMetadataSummary(video);
        String videoUri = resolveVideoUri(video);

        for (FeatureParameter f : targetFeatures) {
          String prompt = promptBuilder.generateSingleFeaturePrompt(f, video, metadataSummary);
          Map<String, String> instanceMeta = new java.util.HashMap<>();
          instanceMeta.put("video_uri", videoUri != null ? videoUri : "");
          instanceMeta.put("video_id", video.getVideoId() != null ? video.getVideoId() : "");
          instanceMeta.put("feature_id", f.getId() != null ? f.getId() : "");
          instanceMeta.put("brand_name", brandName != null ? brandName : "");

          String jsonlInstanceString;
          try {
            jsonlInstanceString = objectMapper.writeValueAsString(instanceMeta);
          } catch (IOException e) {
            jsonlInstanceString = "{}";
          }

          String row = promptBuilder.createRequestPayloadRow(videoUri, prompt, jsonlInstanceString);
          if (!row.isEmpty()) {
            channel.write(ByteBuffer.wrap(row.getBytes(StandardCharsets.UTF_8)));
            channel.write(ByteBuffer.wrap("\n".getBytes(StandardCharsets.UTF_8)));
          }
        }
      }
    }

    String inputGcsUri = String.format("gs://%s/%s", uploadsBucket, objectPath);
    String modelResourceName = String.format("publishers/google/models/%s", modelName);
    String outputGcsPrefix =
        String.format("gs://%s/phase2_batches/%s/output/", uploadsBucket, analysisId);
    String jobDisplayName = String.format("bulkaibcd_phase2_%s", analysisId);

    return vertexBatchJob.createBatchPredictionJob(
        jobDisplayName, modelResourceName, inputGcsUri, outputGcsPrefix);
  }

  @SuppressWarnings("unchecked")
  public void processPhase2Results(String analysisId, String gcsOutputUri) throws Exception {
    log.info(
        "BatchPredictionOrchestrator: Starting Phase 2 fanned-in predictions results processing"
            + " under: {}",
        gcsOutputUri);
    String prefix = gcsOutputUri.replace("gs://", "");
    int slash = prefix.indexOf('/');
    if (slash < 0) throw new IllegalArgumentException("Bad GCS output prefix: " + gcsOutputUri);
    String bucketName = prefix.substring(0, slash);
    String outputPrefix = prefix.substring(slash + 1);

    Map<String, Map<String, ParsedFeature>> videoResults = new HashMap<>();
    var blobs = gcsStorage.listBlobs(bucketName, outputPrefix);
    for (Blob blob : blobs) {
      String name = blob.getName();
      if (!name.endsWith(".jsonl") || !name.contains("prediction")) continue;
      log.info(
          "BatchPredictionOrchestrator: Streaming output predictions blob line-by-line: {}", name);
      try (ReadChannel reader = blob.reader();
          BufferedReader lineReader =
              new BufferedReader(
                  new InputStreamReader(Channels.newInputStream(reader), StandardCharsets.UTF_8))) {
        String line;
        while ((line = lineReader.readLine()) != null) {
          if (line.trim().isEmpty()) continue;
          try {
            Map<String, Object> record =
                objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
            String instanceStr = (String) record.get("instance");
            Map<String, String> instance =
                objectMapper.readValue(instanceStr, new TypeReference<Map<String, String>>() {});
            String videoId = instance.get("video_id");
            String featureId = instance.get("feature_id");

            Map<String, Object> response = (Map<String, Object>) record.get("response");
            if (response != null) {
              List<Map<String, Object>> candidates =
                  (List<Map<String, Object>>) response.get("candidates");
              if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> candidate = candidates.get(0);
                Map<String, Object> contentPart = (Map<String, Object>) candidate.get("content");
                if (contentPart != null) {
                  List<Map<String, Object>> parts =
                      (List<Map<String, Object>>) contentPart.get("parts");
                  if (parts != null && !parts.isEmpty()) {
                    String text = (String) parts.get(0).get("text");
                    String cleanText = text.replace("```json", "").replace("```", "").trim();
                    Map<String, Object> result =
                        objectMapper.readValue(
                            cleanText, new TypeReference<Map<String, Object>>() {});
                    Boolean detected = (Boolean) result.get("detected");
                    Map<String, Object> evaluationMap =
                        (Map<String, Object>) result.get("evaluation");
                    String rationale =
                        (evaluationMap != null) ? (String) evaluationMap.get("rationale") : "";

                    videoResults
                        .computeIfAbsent(videoId, k -> new HashMap<>())
                        .put(
                            featureId,
                            new ParsedFeature(
                                detected != null && detected, rationale != null ? rationale : ""));
                  }
                }
              }
            }
          } catch (Exception e) {
            log.warn("BatchPredictionOrchestrator: Failed to decode predictions row.", e);
          }
        }
      }
    }

    if (videoResults.isEmpty()) {
      if (outputPrefix.contains("FAILED_")) {
        log.warn("BatchPredictionOrchestrator: Triggered results processor under failure gateway.");
      } else {
        throw new IllegalStateException(
            "No predictions metadata parsed successfully for batch: " + analysisId);
      }
    }

    AnalysisRequestEntity parent = analysisRequestRepository.findById(analysisId).block();
    if (parent == null)
      throw new IllegalArgumentException("Analysis parent request not found: " + analysisId);

    String objName =
        parent.getMarketingObjective() == null ? "core_unknown" : parent.getMarketingObjective();
    String type = parent.getAnalysisType();

    Map<String, GuidelineRelevance> relevanceMap = featureConfigService.getGuidelineRelevanceMap();
    List<FeatureParameter> targetFeaturesLong = featureConfigService.getFeaturesByTypeAndFormat(type, VideoFormat.LONG);
    List<FeatureParameter> targetFeaturesShort = featureConfigService.getFeaturesByTypeAndFormat(type, VideoFormat.SHORT);
    Map<String, String> featureNamesMapLong = targetFeaturesLong.stream()
            .collect(Collectors.toMap(FeatureParameter::getId, FeatureParameter::getName, (n1, n2) -> n1));
    Map<String, String> featureNamesMapShort = targetFeaturesShort.stream()
            .collect(Collectors.toMap(FeatureParameter::getId, FeatureParameter::getName, (n1, n2) -> n1));

    videoInputRepository
        .findByAnalysisId(analysisId)
        .flatMap(
            input -> {
              String videoId = input.getVideoId();
              String metadataId = analysisId + "_" + videoId;

              return videoMetadataRepository
                  .findById(metadataId)
                  .flatMap(
                      metadata -> {
                        if (AnalysisStatus.COMPLETED.name().equals(metadata.getStatus())
                            || AnalysisStatus.FAILED.name().equals(metadata.getStatus())) {
                          return Mono.<VideoMetadataEntity>empty();
                        }

                        List<FeatureParameter> targetFeatures = VideoFormat.SHORT.name().equalsIgnoreCase(metadata.getFormat()) ? targetFeaturesShort : targetFeaturesLong;
                        Map<String, String> featureNamesMap = VideoFormat.SHORT.name().equalsIgnoreCase(metadata.getFormat()) ? featureNamesMapShort : featureNamesMapLong;
                        List<String> customFeatures = VideoFormat.SHORT.name().equalsIgnoreCase(metadata.getFormat()) ? parent.getCustomFeaturesShort() : parent.getCustomFeaturesLong();
                        if (customFeatures == null) customFeatures = new ArrayList<>();

                        Map<String, ParsedFeature> predictions =
                            videoResults.getOrDefault(videoId, new HashMap<>());
                        List<String> relevant = new ArrayList<>();
                        List<String> detectedFeatureNames = new ArrayList<>();
                        List<String> notDetected = new ArrayList<>();
                        List<NotDetectedFeatureEntity> notDetectedFeatureNames = new ArrayList<>();
                        StringBuilder recommendationsObj = new StringBuilder();

                        int totalA = 0, totalB = 0, totalC = 0, totalD = 0;
                        int hitA = 0, hitB = 0, hitC = 0, hitD = 0;

                        for (FeatureParameter f : targetFeatures) {
                          String fid = f.getId();
                          GuidelineRelevance relevance = relevanceMap.get(fid);
                          if (relevance == null) continue;

                          boolean isRelevant =
                              dynamicScoring.isGuidelineRelevant(relevance, objName);
                          ParsedFeature pred = predictions.get(fid);
                          boolean detected = pred != null && pred.detected;
                          String rationale = pred != null ? pred.rationale : "";

                          String standardName = featureNamesMap.getOrDefault(fid, fid);
                          if (detected) {
                            detectedFeatureNames.add(standardName);
                          }

                          if (type.equals("custom")) {
                            if (customFeatures.contains(standardName)) isRelevant = true;
                            else isRelevant = false;
                          }

                          if (isRelevant) {
                            relevant.add(standardName);

                            if (fid.startsWith("a_")) totalA++;
                            else if (fid.startsWith("b_")) totalB++;
                            else if (fid.startsWith("c_")) totalC++;
                            else if (fid.startsWith("d_")) totalD++;

                            if (detected) {
                              if (fid.startsWith("a_")) hitA++;
                              else if (fid.startsWith("b_")) hitB++;
                              else if (fid.startsWith("c_")) hitC++;
                              else if (fid.startsWith("d_")) hitD++;
                            } else {
                              notDetected.add(standardName);
                              if (rationale == null || rationale.isBlank()) {
                                rationale = "No Response from gemini";
                              }
                              recommendationsObj.append(rationale).append("\n");
                              notDetectedFeatureNames.add(
                                  new NotDetectedFeatureEntity(standardName, rationale));
                            }
                          }
                        }

                        int scoreA = dynamicScoring.calculateDimensionScore(totalA, hitA);
                        int scoreB = dynamicScoring.calculateDimensionScore(totalB, hitB);
                        int scoreC = dynamicScoring.calculateDimensionScore(totalC, hitC);
                        int scoreD = dynamicScoring.calculateDimensionScore(totalD, hitD);

                        metadata.setFeatures(detectedFeatureNames);
                        metadata.setAScore(scoreA);
                        metadata.setBScore(scoreB);
                        metadata.setCScore(scoreC);
                        metadata.setDScore(scoreD);
                        metadata.setNotDetected(notDetected);
                        metadata.setNotDetectedFeatures(notDetectedFeatureNames);
                        metadata.setRelevantFeatures(relevant);
                        metadata.setRecommendations(recommendationsObj.toString());

                        if (outputPrefix.contains("FAILED_")) {
                          metadata.setStatus(AnalysisStatus.FAILED.name());
                          metadata.setErrorMessage(outputPrefix.replace("FAILED_", ""));
                        } else {
                          metadata.setStatus(AnalysisStatus.COMPLETED.name());
                        }

                        if (SourceType.FILE.name().equalsIgnoreCase(input.getSourceType())
                            && input.getGcsObjectId() != null) {
                          log.info(
                              "BatchPredictionOrchestrator: Auto-GC: Deleting temporary raw video"
                                  + " file bytes: {}",
                              input.getGcsObjectId());
                          gcsStorage.deleteGcsObject(input.getGcsObjectId());
                        }

                        return videoMetadataRepository.save(metadata);
                      });
            },
            8)
        .collectList()
        .block();

    log.info("BatchPredictionOrchestrator: Successfully committed video results to Firestore.");
    gcsStorage.purgeGcsFolder(uploadsBucket, String.format("phase2_batches/%s/", analysisId));

    parent.setAnalysisStatus(AnalysisStatus.COMPLETED.name());
    parent.setUpdatedAt(Timestamp.now());
    analysisRequestRepository.save(parent).block();
    log.info(
        "BatchPredictionOrchestrator: Dynamic Scoring Pipeline concluded successfully. Parent run"
            + " promoted to COMPLETED.");
  }

  private static String resolveVideoUri(VideoInputEntity v) {
    if (v.getGcsObjectId() != null && !v.getGcsObjectId().isEmpty()) {
      return "gs://" + v.getGcsObjectId();
    }
    if (v.getVideoUrl() != null && !v.getVideoUrl().isEmpty()) {
      return v.getVideoUrl();
    }
    return v.getVideoName() == null ? "" : v.getVideoName();
  }

  private static String resolveVideoUri(VideoMetadataEntity v) {
    if (v.getGcsObjectId() != null && !v.getGcsObjectId().isEmpty()) {
      return "gs://" + v.getGcsObjectId();
    }
    if (v.getVideoUrl() != null && !v.getVideoUrl().isEmpty()) {
      return v.getVideoUrl();
    }
    return v.getVideoName() == null ? "" : v.getVideoName();
  }
}
