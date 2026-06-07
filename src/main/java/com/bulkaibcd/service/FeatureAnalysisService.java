package com.bulkaibcd.service;

import com.bulkaibcd.config.AnalysisConstants;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.FeatureParameter;
import com.bulkaibcd.model.GuidelineRelevance;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.aiplatform.v1.BatchPredictionJob;
import com.google.cloud.aiplatform.v1.CreateBatchPredictionJobRequest;
import com.google.cloud.aiplatform.v1.GcsDestination;
import com.google.cloud.aiplatform.v1.GcsSource;
import com.google.cloud.aiplatform.v1.JobServiceClient;
import com.google.cloud.aiplatform.v1.JobServiceSettings;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.Timestamp;
import com.google.cloud.WriteChannel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@RequiredArgsConstructor
public class FeatureAnalysisService {

  private static final String ROLE_USER = "user";
  private static final String MIME_TYPE_VIDEO = "video/mp4";
  private static final double TEMPERATURE = 0.1;
  private static final String NO_METADATA_SUMMARY = "No pre-analyzed structural metadata available.";
  private static final String EMPTY_METADATA_SUMMARY = "Structural metadata files were found but contained no parsable data.";

  private final Storage storage;
  private final FeatureConfigService featureConfigService;
  private final ObjectMapper objectMapper;

  private final VideoMetadataRepository videoMetadataRepository;
  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoInputRepository videoInputRepository;
  private final CloudTasksService cloudTasksService;

  @Value("${google.cloud.project.id}")
  private String projectId;

  @Value("${google.cloud.location:us-central1}")
  private String location;

  @Value("${google.cloud.tasks.queue:bulkaibcd-queue}")
  private String queueId;

  @Value("${google.cloud.model.name:gemini-2.5-flash}")
  private String modelName;

  @Value("${google.cloud.phase1.model.name:gemini-2.5-pro}")
  private String phase1ModelName;

  @Value("${app.uploads-bucket}")
  private String uploadsBucket;

  /** Holds in-memory grouped prediction findings per feature segment */
  private static class ParsedFeature {
    boolean detected;

    ParsedFeature(boolean detected) {
      this.detected = detected;
    }
  }

  /**
   * Builds the comprehensive metadata JSON context from the 14 raw database
   * fields
   */
  public String buildMetadataJson(VideoMetadataEntity metadata, AnalysisRequestEntity parent) {
    Map<String, Object> jsonMap = new HashMap<>();

    String brand = metadata.getBrand();
    if (brand == null || brand.isBlank() || "unknown".equalsIgnoreCase(brand) || "The brand".equalsIgnoreCase(brand)) {
      brand = parent.getBrandName();
    }
    if (brand == null || brand.isBlank()) {
      brand = "The brand";
    }
    jsonMap.put("brand", brand);

    if (metadata.getProduct() != null)
      jsonMap.put("product", metadata.getProduct());
    if (metadata.getVideoLanguage() != null)
      jsonMap.put("language", metadata.getVideoLanguage());
    if (metadata.getVertical() != null)
      jsonMap.put("vertical", metadata.getVertical());
    if (metadata.getAssetName() != null)
      jsonMap.put("assetName", metadata.getAssetName());

    addAnnotationField(jsonMap, "shotAnnotations", metadata.getShot());
    addAnnotationField(jsonMap, "textAnnotations", metadata.getText());
    addAnnotationField(jsonMap, "speechAnnotations", metadata.getSpeech());
    addAnnotationField(jsonMap, "logoAnnotations", metadata.getLogo());
    addAnnotationField(jsonMap, "objectAnnotations", metadata.getObjects());
    addAnnotationField(jsonMap, "faceAnnotations", metadata.getFace());
    addAnnotationField(jsonMap, "labelAnnotations", metadata.getLabelName());
    addAnnotationField(jsonMap, "explicitContentAnnotations", metadata.getExplicit());
    addAnnotationField(jsonMap, "personAnnotations", metadata.getPerson());

    try {
      return objectMapper.writeValueAsString(jsonMap);
    } catch (IOException e) {
      log.error("Failed to build metadata JSON context block string", e);
      return "{}";
    }
  }

  private void addAnnotationField(Map<String, Object> jsonMap, String key, String rawJson) {
    if (rawJson == null || rawJson.trim().isEmpty()) {
      jsonMap.put(key, List.of());
      return;
    }
    String cleanJson = rawJson.trim().replace("```json", "").replace("```", "").trim();
    if (!cleanJson.startsWith("[") || !cleanJson.endsWith("]")) {
      log.warn("Annotation JSON for key '{}' is not a valid JSON array. Storing blank.", key);
      jsonMap.put(key, List.of());
      return;
    }
    try {
      List<Map<String, Object>> parsed = objectMapper.readValue(cleanJson,
          new TypeReference<List<Map<String, Object>>>() {
          });
      jsonMap.put(key, parsed);
    } catch (IOException e) {
      log.error("Failed to parse annotation JSON for key '{}'. Storing blank. Raw: {}", key, rawJson, e);
      jsonMap.put(key, List.of());
    }
  }

  /**
   * Constructs the clean, human-readable bulleted metadata summary text block
   * (pre-analysis hints)
   */
  public String createMetadataSummary(VideoMetadataEntity metadata) {
    StringBuilder sb = new StringBuilder();

    // 1. Speech Transcript
    appendMetadataCategory(sb, "SPEECH TRANSCRIPT (transcript, confidence):", metadata.getSpeech(),
        item -> String.format("- '%s' (conf: %s)\n", item.getOrDefault("transcript", ""),
            item.getOrDefault("confidence", "N/A")));

    // 2. Text Detected
    appendMetadataCategory(sb, "TEXT DETECTED (text, start, end times in seconds):", metadata.getText(),
        item -> String.format("- '%s' @ %s - %ss\n", item.getOrDefault("text", ""),
            item.getOrDefault("start_time", "0.0"), item.getOrDefault("end_time", "0.0")));

    // 3. Logo Detected
    appendMetadataCategory(sb, "LOGO DETECTED (logo_description, start, end times in seconds):", metadata.getLogo(),
        item -> String.format("- '%s' @ %s - %ss\n", item.getOrDefault("logo_description", ""),
            item.getOrDefault("start_time", "0.0"), item.getOrDefault("end_time", "0.0")));

    // 4. Shot Changes
    appendMetadataCategory(sb, "SHOT CHANGES (start, end times in seconds):", metadata.getShot(),
        item -> String.format("- %s - %ss\n", item.getOrDefault("start_time", "0.0"),
            item.getOrDefault("end_time", "0.0")));

    // 5. Objects Detected
    appendMetadataCategory(sb, "OBJECTS DETECTED (description, start, end times in seconds):", metadata.getObjects(),
        item -> String.format("- '%s' @ %s - %ss\n",
            item.getOrDefault("description", item.getOrDefault("object_description", "Object")),
            item.getOrDefault("start_time", "0.0"), item.getOrDefault("end_time", "0.0")));

    // 6. Faces Detected
    appendMetadataCategory(sb, "FACES DETECTED (start, end times in seconds):", metadata.getFace(),
        item -> String.format("- %s - %ss\n", item.getOrDefault("start_time", "0.0"),
            item.getOrDefault("end_time", "0.0")));

    // 7. Labels Detected
    appendMetadataCategory(sb, "LABELS DETECTED (description, start, end times in seconds):", metadata.getLabelName(),
        item -> String.format("- '%s' @ %s - %ss\n",
            item.getOrDefault("description", item.getOrDefault("label", "Label")),
            item.getOrDefault("start_time", "0.0"), item.getOrDefault("end_time", "0.0")));

    // 8. Explicit Content Detected
    appendMetadataCategory(sb, "EXPLICIT CONTENT DETECTED (category, likelihood, start_time, end_time):",
        metadata.getExplicit(),
        item -> String.format("- %s: %s likelihood @ %s - %ss\n", item.getOrDefault("category", ""),
            item.getOrDefault("likelihood", ""), item.getOrDefault("start_time", "0.0"),
            item.getOrDefault("end_time", "0.0")));

    // 9. Persons Detected (complex max visual area coverage matching)
    appendPersonMetadataCategory(sb, metadata.getPerson());

    return sb.length() > 0 ? sb.toString() : EMPTY_METADATA_SUMMARY;
  }

  private void appendMetadataCategory(StringBuilder sb, String title, String rawJson,
      java.util.function.Function<Map<String, Object>, String> formatter) {
    if (rawJson == null || rawJson.trim().isEmpty())
      return;
    String clean = rawJson.trim().replace("```json", "").replace("```", "").trim();
    if (!clean.startsWith("[") || !clean.endsWith("]"))
      return;
    try {
      List<Map<String, Object>> parsed = objectMapper.readValue(clean, new TypeReference<List<Map<String, Object>>>() {
      });
      if (parsed.isEmpty())
        return;

      sb.append("\n").append(title).append("\n");
      for (Map<String, Object> item : parsed) {
        sb.append(formatter.apply(item));
      }
    } catch (IOException e) {
      log.warn("Parsing skipped for category '{}' during summary generation", title, e);
    }
  }

  private void appendPersonMetadataCategory(StringBuilder sb, String rawJson) {
    if (rawJson == null || rawJson.trim().isEmpty())
      return;
    String clean = rawJson.trim().replace("```json", "").replace("```", "").trim();
    if (!clean.startsWith("[") || !clean.endsWith("]"))
      return;
    try {
      List<Map<String, Object>> parsed = objectMapper.readValue(clean, new TypeReference<List<Map<String, Object>>>() {
      });
      if (parsed.isEmpty())
        return;

      sb.append("\nPERSONS DETECTED (start, end times, largest frame coverage):\n");
      for (Map<String, Object> track : parsed) {
        double maxArea = 0.0;
        Object framesObj = track.get("frames");
        if (framesObj instanceof List) {
          List<?> frames = (List<?>) framesObj;
          for (Object frameObj : frames) {
            if (frameObj instanceof Map) {
              Map<?, ?> frame = (Map<?, ?>) frameObj;
              Object boxObj = frame.get("box");
              if (boxObj instanceof Map) {
                Map<?, ?> box = (Map<?, ?>) boxObj;
                double r = getDoubleValue(box.get("r"));
                double l = getDoubleValue(box.get("l"));
                double b = getDoubleValue(box.get("b"));
                double t = getDoubleValue(box.get("t"));
                double width = r - l;
                double height = b - t;
                maxArea = Math.max(maxArea, width * height);
              }
            }
          }
        }
        sb.append(String.format("- Person detected from %ss to %ss. Max frame coverage: %.2f%%\n",
            track.getOrDefault("start_time", "0.0"),
            track.getOrDefault("end_time", "0.0"),
            maxArea * 100));
      }
    } catch (IOException e) {
      log.warn("Person annotations parsing skipped during summary generation", e);
    }
  }

  private double getDoubleValue(Object val) {
    if (val instanceof Number) {
      return ((Number) val).doubleValue();
    }
    return 0.0;
  }

  /** Interpolates parameters into the V1 SINGLE_FEATURE_PROMPT_TEMPLATE */
  public String generateSingleFeaturePrompt(FeatureParameter feature, VideoMetadataEntity metadata,
      String metadataSummary) {
    String templatePrompt = feature.getPromptTemplate();
    String brand = metadata.getBrand() == null ? "" : metadata.getBrand();
    String product = metadata.getProduct() == null ? "" : metadata.getProduct();
    String vertical = metadata.getVertical() == null ? "" : metadata.getVertical();
    String language = metadata.getVideoLanguage() == null ? "" : metadata.getVideoLanguage();
    String assetName = metadata.getAssetName() == null ? "" : metadata.getAssetName();

    templatePrompt = templatePrompt.replace("{brand}", brand);
    templatePrompt = templatePrompt.replace("{product}", product);
    templatePrompt = templatePrompt.replace("{metadata_summary}",
        metadataSummary != null ? metadataSummary : NO_METADATA_SUMMARY);

    String name = feature.getName().replaceAll("\\s+", " ").trim();

    return String.format(
        AnalysisConstants.SINGLE_FEATURE_PROMPT_TEMPLATE,
        name,
        assetName,
        brand,
        product,
        vertical,
        language,
        feature.getCriteria(),
        metadataSummary != null ? metadataSummary : NO_METADATA_SUMMARY,
        templatePrompt);
  }

  /**
   * Assembles a single GCS prediction row matching the Gemini batch predicting
   * schema
   */
  public String createRequestPayloadRow(String videoUri, String prompt, String jsonlInstanceString) {
    Map<String, String> fileData = Map.of("mime_type", MIME_TYPE_VIDEO, "file_uri", videoUri);
    Map<String, Object> filePart = Map.of("file_data", fileData);
    Map<String, Object> textPart = Map.of("text", prompt);

    Map<String, Object> contentUser = Map.of("role", ROLE_USER, "parts", List.of(filePart, textPart));
    Map<String, Object> generationConfig = Map.of("temperature", TEMPERATURE, "response_mime_type", "application/json");

    Map<String, Object> requestPayload = Map.of("contents", List.of(contentUser), "generation_config",
        generationConfig);

    Map<String, Object> jsonLineMap = Map.of("instance", jsonlInstanceString, "request", requestPayload);

    try {
      return objectMapper.writeValueAsString(jsonLineMap);
    } catch (IOException e) {
      log.error("Failed to serialize GCS request payload row object to JSON", e);
      return "";
    }
  }

  /**
   * Assembles a consolidated GCS JSONL file and programmatically launches
   * exactly 1 Vertex AI Batch Prediction Job for the entire analysis run
   */
  public String submitBatchPredictionJob(String analysisId, List<VideoMetadataEntity> videos, String analysisType,
      String brandName) throws IOException {
    log.info("Analysis {}: Starting consolidated batch prediction setup for {} videos...", analysisId, videos.size());

    // 1. Load fanned-out parameter configs
    List<FeatureParameter> targetFeatures = featureConfigService.getFeaturesByType(analysisType);
    if (targetFeatures.isEmpty()) {
      throw new IllegalArgumentException("No features configured on resource mapper for analysisType: " + analysisType);
    }

    // 2. Stream JSONL rows directly to GCS WriteChannel to maintain O(1) memory
    String objectPath = String.format("batch_runs/%s/input.jsonl", analysisId);
    BlobInfo blobInfo = BlobInfo.newBuilder(uploadsBucket, objectPath)
        .setContentType("application/jsonl")
        .build();

    log.info("Analysis {}: Streaming unified input JSONL file directly to GCS: gs://{}/{}", analysisId, uploadsBucket, objectPath);

    try (WriteChannel channel = storage.writer(blobInfo)) {
      for (VideoMetadataEntity v : videos) {
        String metadataSummary = createMetadataSummary(v);
        String videoUri = v.getVideoUrl(); // points to GCS path gs://... or YouTube canonical URL

        for (FeatureParameter f : targetFeatures) {
          String prompt = generateSingleFeaturePrompt(f, v, metadataSummary);

          // Instance metadata block string: mapped under prediction output row
          Map<String, String> instanceMeta = Map.of(
              "video_uri", videoUri,
              "video_id", v.getVideoId(),
              "feature_id", f.getId(),
              "brand_name", brandName != null ? brandName : "");

          String jsonlInstanceString;
          try {
            jsonlInstanceString = objectMapper.writeValueAsString(instanceMeta);
          } catch (IOException e) {
            log.error("Failed to serialize instance meta", e);
            jsonlInstanceString = "{}";
          }

          String row = createRequestPayloadRow(videoUri, prompt, jsonlInstanceString);
          if (!row.isEmpty()) {
            channel.write(ByteBuffer.wrap(row.getBytes(StandardCharsets.UTF_8)));
            channel.write(ByteBuffer.wrap("\n".getBytes(StandardCharsets.UTF_8)));
          }
        }
      }
    }

    String inputGcsUri = String.format("gs://%s/%s", uploadsBucket, objectPath);
    log.info("Analysis {}: Unified input JSONL file successfully staged on GCS: {}", analysisId, inputGcsUri);

    // 4. Programmatically dispatch CreateBatchPredictionJob using Java Vertex SDK
    // JobServiceClient
    String regionEndpoint = location + "-aiplatform.googleapis.com:443";
    log.info("Analysis {}: Connecting to Vertex AI regional endpoint: {}", analysisId, regionEndpoint);

    JobServiceSettings settings = JobServiceSettings.newBuilder()
        .setEndpoint(regionEndpoint)
        .build();

    try (JobServiceClient client = JobServiceClient.create(settings)) {
      String parent = String.format("projects/%s/locations/%s", projectId, location);
      String modelResourceName = String.format("publishers/google/models/%s", modelName);
      String outputGcsPrefix = String.format("gs://%s/batch_runs/%s/output/", uploadsBucket, analysisId);
      String jobDisplayName = String.format("bulkaibcd_audit_%s", analysisId);

      GcsSource gcsSource = GcsSource.newBuilder().addUris(inputGcsUri).build();
      GcsSource inputGcsSource = gcsSource;

      GcsDestination gcsDestination = GcsDestination.newBuilder().setOutputUriPrefix(outputGcsPrefix).build();
      GcsDestination outputGcsDestination = gcsDestination;

      BatchPredictionJob.InputConfig inputConfig = BatchPredictionJob.InputConfig.newBuilder()
          .setInstancesFormat("jsonl")
          .setGcsSource(inputGcsSource)
          .build();

      BatchPredictionJob.OutputConfig outputConfig = BatchPredictionJob.OutputConfig.newBuilder()
          .setPredictionsFormat("jsonl")
          .setGcsDestination(outputGcsDestination)
          .build();

      BatchPredictionJob batchPredictionJob = BatchPredictionJob.newBuilder()
          .setDisplayName(jobDisplayName)
          .setModel(modelResourceName)
          .setInputConfig(inputConfig)
          .setOutputConfig(outputConfig)
          .build();

      CreateBatchPredictionJobRequest request = CreateBatchPredictionJobRequest.newBuilder()
          .setParent(parent)
          .setBatchPredictionJob(batchPredictionJob)
          .build();

      log.info("Analysis {}: Sending createBatchPredictionJob REST call to Vertex Platform...", analysisId);
      BatchPredictionJob launchedJob = client.createBatchPredictionJob(request);
      String batchJobId = launchedJob.getName(); // Stores canonical resource ID
      log.info("Analysis {}: Vertex Batch Prediction successfully launched! Resource Job Name ID: {}", analysisId,
          batchJobId);

      return batchJobId;
    }
  }

  /**
   * Downloads output prediction files from GCS, decodes checklists rationales,
   * maps dynamicdynamic-scoring weights, and commits finalized metrics to
   * Firestore
   */
  @SuppressWarnings("unchecked")
  public void processResultsAndSave(String analysisId, String gcsOutputUri) throws Exception {
    log.info("Analysis {}: Starting fanned-in predictions results processing under: {}", analysisId, gcsOutputUri);

    // 1. Resolve GCS directory coordinates
    String prefix = gcsOutputUri.replace("gs://", "");
    int slash = prefix.indexOf('/');
    if (slash < 0) {
      throw new IllegalArgumentException("Bad GCS output prefix (no slash): " + gcsOutputUri);
    }
    String bucketName = prefix.substring(0, slash);
    String outputPrefix = prefix.substring(slash + 1);

    // Group parsed findings dynamically: Map<VideoId, Map<FeatureId,
    // ParsedFeature>>
    Map<String, Map<String, ParsedFeature>> videoResults = new HashMap<>();

    // 2. Stream and decode predictions files line-by-line to maintain O(1) memory
    var blobs = storage.list(bucketName, Storage.BlobListOption.prefix(outputPrefix));
    for (Blob blob : blobs.iterateAll()) {
      String name = blob.getName();
      if (!name.endsWith(".jsonl") || !name.contains("prediction")) {
        continue;
      }

      log.info("Analysis {}: Streaming output predictions blob line-by-line: {}", analysisId, name);
      try (com.google.cloud.ReadChannel reader = blob.reader();
           java.io.BufferedReader lineReader = new java.io.BufferedReader(new java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(reader), StandardCharsets.UTF_8))) {
        String line;
        while ((line = lineReader.readLine()) != null) {
          if (line.trim().isEmpty())
            continue;
          try {
            // Decode prediction JSON record row
            Map<String, Object> record = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {
            });

            // Read instance metadata block string
            String instanceStr = (String) record.get("instance");
            Map<String, String> instance = objectMapper.readValue(instanceStr, new TypeReference<Map<String, String>>() {
            });

            String videoId = instance.get("video_id");
            String featureId = instance.get("feature_id");

            // Read response parts containing Gemini JSON returns
            Map<String, Object> response = (Map<String, Object>) record.get("response");
            if (response != null) {
              List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
              if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> candidate = candidates.get(0);
                Map<String, Object> contentPart = (Map<String, Object>) candidate.get("content");
                if (contentPart != null) {
                  List<Map<String, Object>> parts = (List<Map<String, Object>>) contentPart.get("parts");
                  if (parts != null && !parts.isEmpty()) {
                    String text = (String) parts.get(0).get("text");
                    String cleanText = text.replace("```json", "").replace("```", "").trim();

                    // Jackson parse single feature prompt json result!
                    Map<String, Object> result = objectMapper.readValue(cleanText,
                        new TypeReference<Map<String, Object>>() {
                        });
                    Boolean detected = (Boolean) result.get("detected");

                    videoResults.computeIfAbsent(videoId, k -> new HashMap<>())
                        .put(featureId,
                            new ParsedFeature(detected != null && detected));
                  }
                }
              }
            }
          } catch (Exception e) {
            log.warn("Analysis {}: Failed to decode predictions row. Skipping line.", analysisId, e);
          }
        }
      }
    }

    if (videoResults.isEmpty()) {
      // Check if this was triggered under failure scenario
      if (outputPrefix.contains("FAILED_")) {
        log.warn("Analysis {}: Triggered results processor under failure gateway. Proceeding with terminal defaults.",
            analysisId);
      } else {
        throw new IllegalStateException("No predictions metadata parsed successfully for batch: " + analysisId);
      }
    }

    // 3. Load parent request details
    AnalysisRequestEntity parent = analysisRequestRepository.findById(analysisId).block();
    if (parent == null)
      throw new IllegalArgumentException("Analysis parent request not found: " + analysisId);

    String objName = parent.getMarketingObjective() == null ? "core_unknown" : parent.getMarketingObjective();
    String type = parent.getAnalysisType() == null ? "STANDARD" : parent.getAnalysisType();

    // Load relevance dynamic-scoring matrix
    Map<String, GuidelineRelevance> relevanceMap = featureConfigService.getGuidelineRelevanceMap();

    // Load feature configurations mapping parameter_id to parameter names
    List<FeatureParameter> targetFeatures = featureConfigService.getFeaturesByType(type);
    Map<String, String> featureNamesMap = targetFeatures.stream()
        .collect(Collectors.toMap(FeatureParameter::getId, FeatureParameter::getName, (n1, n2) -> n1));

    // 4. Stream video inputs and process metadata updates reactively with sharded concurrency
    videoInputRepository.findByAnalysisId(analysisId)
        .flatMap(input -> {
          String videoId = input.getVideoId();
          String metadataId = analysisId + "_" + videoId;

          return videoMetadataRepository.findById(metadataId)
              .flatMap(metadata -> {
                if ("COMPLETED".equals(metadata.getStatus()) || "FAILED".equals(metadata.getStatus())) {
                  return Mono.<VideoMetadataEntity>empty();
                }

                // Read parsed findings for this specific video
                Map<String, ParsedFeature> predictions = videoResults.getOrDefault(videoId, new HashMap<>());

                List<String> detectedFeatureNames = new ArrayList<>();

                int totalA = 0, totalB = 0, totalC = 0, totalD = 0;
                int hitA = 0, hitB = 0, hitC = 0, hitD = 0;

                for (FeatureParameter f : targetFeatures) {
                  String fid = f.getId();
                  GuidelineRelevance relevance = relevanceMap.get(fid);
                  if (relevance == null)
                    continue;

                  boolean isRelevant = isGuidelineRelevant(relevance, objName);
                  ParsedFeature pred = predictions.get(fid);
                  boolean detected = pred != null && pred.detected;

                  String standardName = featureNamesMap.getOrDefault(fid, fid);
                  if (detected) {
                    detectedFeatureNames.add(standardName);
                  }

                  if (isRelevant) {
                    if (fid.startsWith("a_"))
                      totalA++;
                    else if (fid.startsWith("b_"))
                      totalB++;
                    else if (fid.startsWith("c_"))
                      totalC++;
                    else if (fid.startsWith("d_"))
                      totalD++;

                    if (detected) {
                      if (fid.startsWith("a_"))
                        hitA++;
                      else if (fid.startsWith("b_"))
                        hitB++;
                      else if (fid.startsWith("c_"))
                        hitC++;
                      else if (fid.startsWith("d_"))
                        hitD++;
                    }
                  }
                }

                // Calculate dynamic score percentages (Dynamic Scoring Math)
                int scoreA = totalA > 0 ? (int) Math.round((double) hitA / totalA * 100.0) : 100;
                int scoreB = totalB > 0 ? (int) Math.round((double) hitB / totalB * 100.0) : 100;
                int scoreC = totalC > 0 ? (int) Math.round((double) hitC / totalC * 100.0) : 100;
                int scoreD = totalD > 0 ? (int) Math.round((double) hitD / totalD * 100.0) : 100;

                metadata.setFeatures(detectedFeatureNames);
                metadata.setAScore(scoreA);
                metadata.setBScore(scoreB);
                metadata.setCScore(scoreC);
                metadata.setDScore(scoreD);

                if (outputPrefix.contains("FAILED_")) {
                  metadata.setStatus("FAILED");
                  metadata.setErrorMessage(outputPrefix.replace("FAILED_", ""));
                } else {
                  metadata.setStatus("COMPLETED");
                }

                // Trigger temporary storage garbage collection cleans for raw video file uploads
                if ("file".equals(input.getSourceType()) && input.getGcsObjectId() != null) {
                  log.info("Analysis {}: Auto-GC: Deleting temporary raw video file bytes: {}", analysisId,
                      input.getGcsObjectId());
                  deleteGcsObject(input.getGcsObjectId());
                }

                return videoMetadataRepository.save(metadata);
              });
        }, 8)
        .collectList()
        .block();

    log.info("Analysis {}: Successfully committed video results to Firestore.", analysisId);

    // 5. Clean up GCS JSONL batch directories
    log.info("Analysis {}: Auto-GC: Deleting temporary GCS input JSONL and output logs folders...", analysisId);
    purgeGcsFolder(String.format("batch_runs/%s/", analysisId));

    // 6. Promote parent request status to finished
    parent.setAnalysisStatus("COMPLETED");
    parent.setUpdatedAt(Timestamp.now());
    analysisRequestRepository.save(parent).block();
    log.info("Analysis {}: Dynamic Scoring Pipeline concluded successfully. Parent run promoted to COMPLETED.",
        analysisId);
  }

  private boolean isGuidelineRelevant(GuidelineRelevance r, String objective) {
    return switch (objective) {
      case "awareness" -> r.getAwareness() == 1;
      case "consideration" -> r.getConsideration() == 1;
      case "conversion" -> r.getAction() == 1;
      default -> r.getCore() == 1; // core / unknown
    };
  }

  private void deleteGcsObject(String gcsObjectId) {
    if (gcsObjectId == null || gcsObjectId.isBlank())
      return;
    int slash = gcsObjectId.indexOf('/');
    if (slash < 0)
      return;
    String b = gcsObjectId.substring(0, slash);
    String o = gcsObjectId.substring(slash + 1);
    try {
      storage.delete(b, o);
    } catch (Exception e) {
      log.warn("Auto-GC: Failed to delete raw video: {}", gcsObjectId, e);
    }
  }

  private void purgeGcsFolder(String prefix) {
    try {
      var blobs = storage.list(uploadsBucket, Storage.BlobListOption.prefix(prefix));
      List<BlobId> blobIds = new ArrayList<>();
      for (Blob blob : blobs.iterateAll()) {
        blobIds.add(blob.getBlobId());
      }
      if (!blobIds.isEmpty()) {
        storage.delete(blobIds);
        log.info("Auto-GC: Purged {} temporary files in folder: {}", blobIds.size(), prefix);
      }
    } catch (Exception e) {
      log.warn("Auto-GC: Failed to purge temporary folder: {}", prefix, e);
    }
  }

  public String createPhase1RequestPayloadRow(String videoUri, String prompt, String jsonlInstanceString, boolean isJson) {
    Map<String, String> fileData = Map.of("mime_type", MIME_TYPE_VIDEO, "file_uri", videoUri);
    Map<String, Object> filePart = Map.of("file_data", fileData);
    Map<String, Object> textPart = Map.of("text", prompt);
    Map<String, Object> contentUser = Map.of("role", ROLE_USER, "parts", List.of(filePart, textPart));
    Map<String, Object> generationConfig = isJson
        ? Map.of("temperature", TEMPERATURE, "response_mime_type", "application/json")
        : Map.of("temperature", TEMPERATURE);
    Map<String, Object> requestPayload = Map.of("contents", List.of(contentUser), "generation_config", generationConfig);
    Map<String, Object> jsonLineMap = Map.of("instance", jsonlInstanceString, "request", requestPayload);
    try {
      return objectMapper.writeValueAsString(jsonLineMap);
    } catch (IOException e) {
      log.error("Failed to serialize GCS request payload row object to JSON for Phase 1", e);
      return "";
    }
  }

  public String submitPhase1BatchPredictionJob(String analysisId, List<VideoInputEntity> videos) throws IOException {
    log.info("Analysis {}: Starting Phase 1 consolidated batch prediction setup for {} videos...", analysisId, videos.size());
    List<VideoInputEntity> validVideos = videos.stream()
        .filter(v -> v.getErrorMessage() == null || v.getErrorMessage().isEmpty())
        .collect(Collectors.toList());
    if (validVideos.isEmpty()) {
      log.warn("Analysis {}: No valid videos to process for Phase 1 batch run.", analysisId);
      AnalysisRequestEntity parent = analysisRequestRepository.findById(analysisId).block();
      if (parent != null) {
        parent.setAnalysisStatus("COMPLETED");
        parent.setUpdatedAt(Timestamp.now());
        analysisRequestRepository.save(parent).block();
      }
      return "NO_VALID_VIDEOS";
    }
    String objectPath = String.format("phase1_batches/%s/input.jsonl", analysisId);
    BlobInfo blobInfo = BlobInfo.newBuilder(uploadsBucket, objectPath)
        .setContentType("application/jsonl")
        .build();
    log.info("Analysis {}: Streaming Phase 1 input JSONL file directly to GCS: gs://{}/{}", analysisId, uploadsBucket, objectPath);
    try (WriteChannel channel = storage.writer(blobInfo)) {
      for (VideoInputEntity v : validVideos) {
        String videoUri = resolveVideoUri(v);
        for (String metadataType : AnalysisConstants.METADATA_TYPES) {
          String prompt = AnalysisConstants.RAW_PROMPT_MAP.get(metadataType);
          if (prompt == null) continue;
          Map<String, String> instanceMeta = Map.of(
              "video_uri", videoUri,
              "video_id", v.getVideoId(),
              "prompt_type", metadataType
          );
          String jsonlInstanceString;
          try {
            jsonlInstanceString = objectMapper.writeValueAsString(instanceMeta);
          } catch (IOException e) {
            log.error("Failed to serialize instance meta for Phase 1", e);
            jsonlInstanceString = "{}";
          }
          boolean isJson = !List.of("BRAND", "PRODUCT", "LANGUAGE", "VERTICAL", "ASSET_NAME").contains(metadataType);
          String row = createPhase1RequestPayloadRow(videoUri, prompt, jsonlInstanceString, isJson);
          if (!row.isEmpty()) {
            channel.write(ByteBuffer.wrap(row.getBytes(StandardCharsets.UTF_8)));
            channel.write(ByteBuffer.wrap("\n".getBytes(StandardCharsets.UTF_8)));
          }
        }
      }
    }
    String inputGcsUri = String.format("gs://%s/%s", uploadsBucket, objectPath);
    log.info("Analysis {}: Phase 1 input JSONL file successfully staged on GCS: {}", analysisId, inputGcsUri);
    String regionEndpoint = location + "-aiplatform.googleapis.com:443";
    log.info("Analysis {}: Connecting to Vertex AI regional endpoint for Phase 1: {}", analysisId, regionEndpoint);
    JobServiceSettings settings = JobServiceSettings.newBuilder()
        .setEndpoint(regionEndpoint)
        .build();
    try (JobServiceClient client = JobServiceClient.create(settings)) {
      String parent = String.format("projects/%s/locations/%s", projectId, location);
      String modelResourceName = String.format("publishers/google/models/%s", phase1ModelName);
      String outputGcsPrefix = String.format("gs://%s/phase1_batches/%s/output/", uploadsBucket, analysisId);
      String jobDisplayName = String.format("bulkaibcd_phase1_%s", analysisId);
      GcsSource gcsSource = GcsSource.newBuilder().addUris(inputGcsUri).build();
      GcsDestination gcsDestination = GcsDestination.newBuilder().setOutputUriPrefix(outputGcsPrefix).build();
      BatchPredictionJob.InputConfig inputConfig = BatchPredictionJob.InputConfig.newBuilder()
          .setInstancesFormat("jsonl")
          .setGcsSource(gcsSource)
          .build();
      BatchPredictionJob.OutputConfig outputConfig = BatchPredictionJob.OutputConfig.newBuilder()
          .setPredictionsFormat("jsonl")
          .setGcsDestination(gcsDestination)
          .build();
      BatchPredictionJob batchPredictionJob = BatchPredictionJob.newBuilder()
          .setDisplayName(jobDisplayName)
          .setModel(modelResourceName)
          .setInputConfig(inputConfig)
          .setOutputConfig(outputConfig)
          .build();
      CreateBatchPredictionJobRequest request = CreateBatchPredictionJobRequest.newBuilder()
          .setParent(parent)
          .setBatchPredictionJob(batchPredictionJob)
          .build();
      log.info("Analysis {}: Sending Phase 1 createBatchPredictionJob REST call to Vertex Platform...", analysisId);
      BatchPredictionJob launchedJob = client.createBatchPredictionJob(request);
      String batchJobId = launchedJob.getName();
      log.info("Analysis {}: Vertex Phase 1 Batch Prediction successfully launched! Resource Job Name ID: {}", analysisId, batchJobId);
      return batchJobId;
    }
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

  @SuppressWarnings("unchecked")
  public void processPhase1ResultsAndSave(String analysisId, String gcsOutputUri) throws Exception {
    log.info("Analysis {}: Starting Phase 1 fanned-in predictions results processing under: {}", analysisId, gcsOutputUri);
    String prefix = gcsOutputUri.replace("gs://", "");
    int slash = prefix.indexOf('/');
    if (slash < 0) {
      throw new IllegalArgumentException("Bad GCS output prefix (no slash): " + gcsOutputUri);
    }
    String bucketName = prefix.substring(0, slash);
    String outputPrefix = prefix.substring(slash + 1);
    // Map<VideoId, Map<PromptType, String>>
    Map<String, Map<String, String>> videoResults = new HashMap<>();
    var blobs = storage.list(bucketName, Storage.BlobListOption.prefix(outputPrefix));
    for (Blob blob : blobs.iterateAll()) {
      String name = blob.getName();
      if (!name.endsWith(".jsonl") || !name.contains("prediction")) {
        continue;
      }
      log.info("Analysis {}: Streaming Phase 1 output predictions blob line-by-line: {}", analysisId, name);
      try (com.google.cloud.ReadChannel reader = blob.reader();
           java.io.BufferedReader lineReader = new java.io.BufferedReader(new java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(reader), StandardCharsets.UTF_8))) {
        String line;
        while ((line = lineReader.readLine()) != null) {
          if (line.trim().isEmpty()) continue;
          try {
            Map<String, Object> record = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
            String instanceStr = (String) record.get("instance");
            Map<String, String> instance = objectMapper.readValue(instanceStr, new TypeReference<Map<String, String>>() {});
            String videoId = instance.get("video_id");
            String promptType = instance.get("prompt_type");
            Map<String, Object> response = (Map<String, Object>) record.get("response");
            String resultText = "";
            if (response != null) {
              List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
              if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> candidate = candidates.get(0);
                Map<String, Object> contentPart = (Map<String, Object>) candidate.get("content");
                if (contentPart != null) {
                  List<Map<String, Object>> parts = (List<Map<String, Object>>) contentPart.get("parts");
                  if (parts != null && !parts.isEmpty()) {
                    resultText = (String) parts.get(0).get("text");
                  }
                }
              }
            }
            if (resultText == null) resultText = "";
            videoResults.computeIfAbsent(videoId, k -> new HashMap<>()).put(promptType, resultText);
          } catch (Exception e) {
            log.warn("Analysis {}: Failed to decode Phase 1 predictions row. Skipping line.", analysisId, e);
          }
        }
      }
    }
    if (videoResults.isEmpty()) {
      if (outputPrefix.contains("FAILED_")) {
        log.warn("Analysis {}: Triggered Phase 1 results processor under failure gateway. Proceeding with terminal defaults.", analysisId);
      } else {
        throw new IllegalStateException("No Phase 1 predictions metadata parsed successfully for batch: " + analysisId);
      }
    }
    AnalysisRequestEntity parent = analysisRequestRepository.findById(analysisId).block();
    if (parent == null) {
      throw new IllegalArgumentException("Analysis parent request not found: " + analysisId);
    }
    log.info("Analysis {}: Updating metadata in Firestore for all videos...", analysisId);
    videoInputRepository.findByAnalysisId(analysisId)
        .flatMap(input -> {
          String videoId = input.getVideoId();
          String metadataId = analysisId + "_" + videoId;
          return videoMetadataRepository.findById(metadataId)
              .defaultIfEmpty(VideoMetadataEntity.builder().id(metadataId).analysisId(analysisId).videoId(videoId).status("PROCESSING").build())
              .flatMap(metadata -> {
                Map<String, String> preds = videoResults.getOrDefault(videoId, new HashMap<>());
                for (String promptType : AnalysisConstants.METADATA_TYPES) {
                  String val = preds.get(promptType);
                  if (val != null && !val.isEmpty()) {
                    updateRawMetadataField(metadata, promptType, val);
                  } else {
                    fillTerminalRawFailure(metadata, promptType);
                  }
                }
                if (outputPrefix.contains("FAILED_")) {
                  metadata.setStatus("FAILED");
                  metadata.setErrorMessage(outputPrefix.replace("FAILED_", ""));
                } else {
                  metadata.setStatus("METADATA_EXTRACTED");
                }
                return videoMetadataRepository.save(metadata)
                    .onErrorResume(err -> {
                      log.warn("Analysis {}: Firestore save failed for video {} (likely >1MB limit). Error: {}. Stripping heavy annotation fields and retrying.",
                          analysisId, videoId, err.getMessage());
                      metadata.setLabelName("[]");
                      metadata.setObjects("[]");
                      metadata.setPerson("[]");
                      metadata.setFace("[]");
                      metadata.setText("[]");
                      metadata.setLogo("[]");
                      return videoMetadataRepository.save(metadata)
                          .onErrorResume(err2 -> {
                            log.error("Analysis {}: Second Firestore save attempt also failed for video {}. Error: {}. Stripping all annotations.",
                                analysisId, videoId, err2.getMessage());
                            metadata.setSpeech("[]");
                            metadata.setShot("[]");
                            metadata.setExplicit("[]");
                            return videoMetadataRepository.save(metadata);
                          });
                    });
              });
        }, 8)
        .collectList()
        .block();
    log.info("Analysis {}: Successfully committed Phase 1 video results to Firestore.", analysisId);
    log.info("Analysis {}: Auto-GC: Deleting temporary Phase 1 GCS input JSONL and output logs folders...", analysisId);
    purgeGcsFolder(String.format("phase1_batches/%s/", analysisId));
    if (!outputPrefix.contains("FAILED_")) {
      log.info("Analysis {}: Phase 1 completed successfully. Enqueuing Phase 2 start task.", analysisId);
      String taskSuffix = String.format("%s_START_FEATURE_ANALYSIS", analysisId);
      String payload = String.format("{\"analysisId\":\"%s\"}", analysisId);
      try {
        cloudTasksService.enqueueTask("/api/v2/worker/start-feature-analysis", payload, taskSuffix, null);
        log.info("Worker: Phase 2 start task successfully enqueued for run: {}", analysisId);
      } catch (IOException e) {
        log.error("Worker: Failed to trigger Phase 2 for run: {}", analysisId, e);
        throw new RuntimeException(e);
      }
    }
  }

  private void updateRawMetadataField(VideoMetadataEntity m, String type, String val) {
    String cleaned = stripQuotes(val);
    if (cleaned != null && cleaned.length() > 400000) {
      byte[] bytes = cleaned.getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 400000) {
        log.warn("Proactive Auto-GC: Field {} for video {} is massive ({} bytes). Truncating to empty array to prevent Firestore 1MB document limit error.",
            type, m.getVideoId(), bytes.length);
        fillTerminalRawFailure(m, type);
        return;
      }
    }
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

  private void fillTerminalRawFailure(VideoMetadataEntity m, String promptType) {
    switch (promptType) {
      case "BRAND" -> {
        if (m.getBrand() == null) m.setBrand("");
      }
      case "PRODUCT" -> {
        if (m.getProduct() == null) m.setProduct("");
      }
      case "LANGUAGE" -> {
        if (m.getVideoLanguage() == null) m.setVideoLanguage("zxx");
      }
      case "VERTICAL" -> {
        if (m.getVertical() == null) m.setVertical("");
      }
      case "ASSET_NAME" -> {
        if (m.getAssetName() == null) m.setAssetName("");
      }
      case "SPEECH_TRANSCRIPTION" -> {
        if (m.getSpeech() == null) m.setSpeech("[]");
      }
      case "TEXT_DETECTION" -> {
        if (m.getText() == null) m.setText("[]");
      }
      case "SHOT_CHANGE_DETECTION" -> {
        if (m.getShot() == null) m.setShot("[]");
      }
      case "LOGO_RECOGNITION" -> {
        if (m.getLogo() == null) m.setLogo("[]");
      }
      case "OBJECT_TRACKING" -> {
        if (m.getObjects() == null) m.setObjects("[]");
      }
      case "FACE_DETECTION" -> {
        if (m.getFace() == null) m.setFace("[]");
      }
      case "PERSON_DETECTION" -> {
        if (m.getPerson() == null) m.setPerson("[]");
      }
      case "LABEL_DETECTION" -> {
        if (m.getLabelName() == null) m.setLabelName("[]");
      }
      case "EXPLICIT_CONTENT_DETECTION" -> {
        if (m.getExplicit() == null) m.setExplicit("[]");
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

}
