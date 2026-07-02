package com.bulkaibcd.service.batch;

import com.bulkaibcd.config.AnalysisConstants;
import com.bulkaibcd.enums.RawMetadataType;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.FeatureParameter;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Service responsible for encapsulating prompt templating, string unquoting, dynamic metadata
 * summary generation, and Gemini GCS JSONL payload assembly.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PromptBuilderService {

  private static final String ROLE_USER = "user";
  private static final String MIME_TYPE_VIDEO = "video/mp4";
  private static final double TEMPERATURE = 0.1;
  private static final String NO_METADATA_SUMMARY =
      "No pre-analyzed structural metadata available.";

  private final ObjectMapper objectMapper;

  public String buildMetadataJson(VideoMetadataEntity metadata, AnalysisRequestEntity parent) {
    Map<String, Object> jsonMap = new HashMap<>();

    String brand = metadata.getBrand();
    if (brand == null
        || brand.isBlank()
        || "unknown".equalsIgnoreCase(brand)
        || "The brand".equalsIgnoreCase(brand)) {
      brand = parent.getBrandName();
    }
    if (brand == null || brand.isBlank()) {
      brand = "The brand";
    }
    jsonMap.put("brand", brand);

    if (metadata.getProduct() != null) jsonMap.put("product", metadata.getProduct());
    if (metadata.getVideoLanguage() != null) jsonMap.put("language", metadata.getVideoLanguage());
    if (metadata.getVertical() != null) jsonMap.put("vertical", metadata.getVertical());
    if (metadata.getAssetName() != null) jsonMap.put("assetName", metadata.getAssetName());

    addAnnotationField(jsonMap, "shotAnnotations", metadata.getShot());
    addAnnotationField(jsonMap, "textAnnotations", metadata.getText());
    addAnnotationField(jsonMap, "speechAnnotations", metadata.getSpeech());
    addAnnotationField(jsonMap, "logoAnnotations", tutorial(metadata.getLogo()));
    addAnnotationField(jsonMap, "objectAnnotations", metadata.getObjects());
    addAnnotationField(jsonMap, "faceAnnotations", metadata.getFace());
    addAnnotationField(jsonMap, "labelAnnotations", metadata.getLabelName());
    addAnnotationField(jsonMap, "explicitContentAnnotations", metadata.getExplicit());
    addAnnotationField(jsonMap, "personAnnotations", metadata.getPerson());

    try {
      return objectMapper.writeValueAsString(jsonMap);
    } catch (IOException e) {
      log.error("PromptBuilderService: Failed to build metadata JSON context string", e);
      return "{}";
    }
  }

  private String tutorial(String s) {
    return s;
  }

  private void addAnnotationField(Map<String, Object> jsonMap, String key, String rawJson) {
    if (rawJson == null || rawJson.trim().isEmpty()) {
      jsonMap.put(key, List.of());
      return;
    }
    String cleanJson = rawJson.trim().replace("```json", "").replace("```", "").trim();
    if (!cleanJson.startsWith("[") || !cleanJson.endsWith("]")) {
      jsonMap.put(key, List.of());
      return;
    }
    try {
      List<Map<String, Object>> parsed =
          objectMapper.readValue(cleanJson, new TypeReference<List<Map<String, Object>>>() {});
      jsonMap.put(key, parsed);
    } catch (IOException e) {
      jsonMap.put(key, List.of());
    }
  }

  public String createMetadataSummary(VideoMetadataEntity metadata) {
    StringBuilder sb = new StringBuilder();

    appendMetadataCategory(
        sb,
        "SPEECH TRANSCRIPT (transcript, confidence):",
        metadata.getSpeech(),
        item ->
            String.format(
                "- '%s' (conf: %s)\n",
                item.getOrDefault("transcript", ""), item.getOrDefault("confidence", "N/A")));

    appendMetadataCategory(
        sb,
        "TEXT DETECTED (text, start, end times in seconds):",
        metadata.getText(),
        item ->
            String.format(
                "- '%s' @ %s - %ss\n",
                item.getOrDefault("text", ""),
                item.getOrDefault("start_time", "0.0"),
                item.getOrDefault("end_time", "0.0")));

    appendMetadataCategory(
        sb,
        "LOGO DETECTED (logo_description, start, end times in seconds):",
        metadata.getLogo(),
        item ->
            String.format(
                "- '%s' @ %s - %ss\n",
                item.getOrDefault("logo_description", ""),
                item.getOrDefault("start_time", "0.0"),
                item.getOrDefault("end_time", "0.0")));

    appendMetadataCategory(
        sb,
        "SHOT CHANGES (start, end times in seconds):",
        metadata.getShot(),
        item ->
            String.format(
                "- %s - %ss\n",
                item.getOrDefault("start_time", "0.0"), item.getOrDefault("end_time", "0.0")));

    appendMetadataCategory(
        sb,
        "OBJECTS DETECTED (object_description, start, end times in seconds):",
        metadata.getObjects(),
        item ->
            String.format(
                "- '%s' @ %s - %ss\n",
                item.getOrDefault("object_description", ""),
                item.getOrDefault("start_time", "0.0"),
                item.getOrDefault("end_time", "0.0")));

    appendMetadataCategory(
        sb,
        "FACES DETECTED (confidence, start, end times in seconds):",
        metadata.getFace(),
        item ->
            String.format(
                "- Face detected @ %s - %ss (conf: %s)\n",
                item.getOrDefault("start_time", "0.0"),
                item.getOrDefault("end_time", "0.0"),
                item.getOrDefault("confidence", "N/A")));

    appendPersonAnnotations(sb, metadata.getPerson());

    appendMetadataCategory(
        sb,
        "LABELS DETECTED (label, confidence, start, end times in seconds):",
        metadata.getLabelName(),
        item ->
            String.format(
                "- '%s' @ %s - %ss (conf: %s)\n",
                item.getOrDefault("label", ""),
                item.getOrDefault("start_time", "0.0"),
                item.getOrDefault("end_time", "0.0"),
                item.getOrDefault("confidence", "N/A")));

    appendMetadataCategory(
        sb,
        "EXPLICIT CONTENT DETECTED (category, likelihood, start, end times in seconds):",
        metadata.getExplicit(),
        item ->
            String.format(
                "- '%s' @ %s - %ss (likelihood: %s)\n",
                item.getOrDefault("category", ""),
                item.getOrDefault("start_time", "0.0"),
                item.getOrDefault("end_time", "0.0"),
                item.getOrDefault("likelihood", "N/A")));

    return sb.toString();
  }

  private void appendMetadataCategory(
      StringBuilder sb,
      String header,
      String rawJson,
      Function<Map<String, Object>, String> formatter) {
    if (rawJson == null || rawJson.trim().isEmpty()) return;
    String clean = rawJson.trim().replace("```json", "").replace("```", "").trim();
    if (!clean.startsWith("[") || !clean.endsWith("]")) return;
    try {
      List<Map<String, Object>> parsed =
          objectMapper.readValue(clean, new TypeReference<List<Map<String, Object>>>() {});
      if (parsed.isEmpty()) return;
      sb.append("\n").append(header).append("\n");
      for (Map<String, Object> item : parsed) {
        sb.append(formatter.apply(item));
      }
    } catch (IOException e) {
      log.warn("PromptBuilderService: Annotation parsing skipped during summary generation", e);
    }
  }

  private void appendPersonAnnotations(StringBuilder sb, String rawJson) {
    if (rawJson == null || rawJson.trim().isEmpty()) return;
    String clean = rawJson.trim().replace("```json", "").replace("```", "").trim();
    if (!clean.startsWith("[") || !clean.endsWith("]")) return;
    try {
      List<Map<String, Object>> parsed =
          objectMapper.readValue(clean, new TypeReference<List<Map<String, Object>>>() {});
      if (parsed.isEmpty()) return;

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
        sb.append(
            String.format(
                "- Person detected from %ss to %ss. Max frame coverage: %.2f%%\n",
                track.getOrDefault("start_time", "0.0"),
                track.getOrDefault("end_time", "0.0"),
                maxArea * 100));
      }
    } catch (IOException e) {
      log.warn(
          "PromptBuilderService: Person annotations parsing skipped during summary generation", e);
    }
  }

  private double getDoubleValue(Object val) {
    if (val instanceof Number) {
      return ((Number) val).doubleValue();
    }
    return 0.0;
  }

  public String generateSingleFeaturePrompt(
      FeatureParameter feature, VideoMetadataEntity metadata, String metadataSummary) {
    String templatePrompt = feature.getPromptTemplate();
    String brand = metadata.getBrand() == null ? "" : metadata.getBrand();
    String product = metadata.getProduct() == null ? "" : metadata.getProduct();
    String vertical = metadata.getVertical() == null ? "" : metadata.getVertical();
    String language = metadata.getVideoLanguage() == null ? "" : metadata.getVideoLanguage();
    String assetName = metadata.getAssetName() == null ? "" : metadata.getAssetName();

    templatePrompt = templatePrompt.replace("{brand}", brand);
    templatePrompt = templatePrompt.replace("{product}", product);
    templatePrompt =
        templatePrompt.replace(
            "{metadata_summary}", metadataSummary != null ? metadataSummary : NO_METADATA_SUMMARY);

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

  public String createRequestPayloadRow(
      String videoUri, String prompt, String jsonlInstanceString) {
    Map<String, String> fileData = new java.util.HashMap<>();
    fileData.put("mime_type", MIME_TYPE_VIDEO);
    fileData.put("file_uri", videoUri != null ? videoUri : "");
    Map<String, Object> filePart = Map.of("file_data", fileData);
    Map<String, Object> textPart = Map.of("text", prompt);

    Map<String, Object> contentUser =
        Map.of("role", ROLE_USER, "parts", List.of(filePart, textPart));
    Map<String, Object> generationConfig =
        Map.of("temperature", TEMPERATURE, "response_mime_type", "application/json");
    Map<String, Object> requestPayload =
        Map.of("contents", List.of(contentUser), "generation_config", generationConfig);
    Map<String, Object> jsonLineMap =
        Map.of("instance", jsonlInstanceString, "request", requestPayload);

    try {
      return objectMapper.writeValueAsString(jsonLineMap);
    } catch (IOException e) {
      log.error(
          "PromptBuilderService: Failed to serialize GCS request payload row object to JSON", e);
      return "";
    }
  }

  public String createPhase1RequestPayloadRow(
      String videoUri, String prompt, String jsonlInstanceString, boolean isJson) {
    Map<String, String> fileData = new java.util.HashMap<>();
    fileData.put("mime_type", MIME_TYPE_VIDEO);
    fileData.put("file_uri", videoUri != null ? videoUri : "");
    Map<String, Object> filePart = Map.of("file_data", fileData);
    Map<String, Object> textPart = Map.of("text", prompt);
    Map<String, Object> contentUser =
        Map.of("role", ROLE_USER, "parts", List.of(filePart, textPart));
    Map<String, Object> generationConfig =
        isJson
            ? Map.of("temperature", TEMPERATURE, "response_mime_type", "application/json")
            : Map.of("temperature", TEMPERATURE);
    Map<String, Object> requestPayload =
        Map.of("contents", List.of(contentUser), "generation_config", generationConfig);
    Map<String, Object> jsonLineMap =
        Map.of("instance", jsonlInstanceString, "request", requestPayload);
    try {
      return objectMapper.writeValueAsString(jsonLineMap);
    } catch (IOException e) {
      log.error(
          "PromptBuilderService: Failed to serialize GCS request payload row object to JSON for"
              + " Phase 1",
          e);
      return "";
    }
  }

  public void updateRawMetadataField(VideoMetadataEntity m, String type, String val) {
    if (type == null) return;
    String cleaned = stripQuotes(val);
    if (cleaned != null && cleaned.length() > 400000) {
      byte[] bytes = cleaned.getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 400000) {
        log.warn(
            "PromptBuilderService: Field {} for video {} is massive ({} bytes). Truncating to empty"
                + " array.",
            type,
            m.getVideoId(),
            bytes.length);
        fillTerminalRawFailure(m, type);
        return;
      }
    }
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
      log.warn("PromptBuilderService: Unknown RawMetadataType: {}", type);
    }
  }

  public void fillTerminalRawFailure(VideoMetadataEntity m, String promptType) {
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
      log.warn("PromptBuilderService: Unknown RawMetadataType: {}", promptType);
    }
  }

  @Nullable
  public static String stripQuotes(@Nullable String text) {
    if (text == null) return null;
    String trimmed = text.trim();
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      return trimmed.substring(1, trimmed.length() - 1);
    }
    return trimmed;
  }
}
