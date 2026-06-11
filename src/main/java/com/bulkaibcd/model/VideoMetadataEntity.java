package com.bulkaibcd.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.spring.data.firestore.Document;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document(collectionName = "video_metadata")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoMetadataEntity {
  @DocumentId private String id;
  private String analysisId;
  private String videoId;
  private String videoName;
  private String videoUrl;
  /** JPEG data URL captured client-side on upload for local files; null for URL sources. */
  private String thumbnailUrl;
  private String sourceType;
  private String status;
  private String errorMessage;

  // ABCD scoring (0-100). Nullable until the corresponding Gemini prompt returns.
  // Jackson's bean introspector lowercases single-letter prefixes on getters like
  // getAScore() to "ascore" — force the property name so the UI can read it.
  @JsonProperty("aScore") private Integer aScore;
  @JsonProperty("bScore") private Integer bScore;
  @JsonProperty("cScore") private Integer cScore;
  @JsonProperty("dScore") private Integer dScore;

  /**
   * Names of ABCD features Gemini detected in this video (e.g. "early_brand_presence",
   * "clear_cta"). Consumed by the pitch-deck template to flip the {@code ○} placeholder bullets to
   * {@code ●} for detected items.
   */
  private List<String> relevantFeatures;

  private List<String> features;
  private List<String> notDetected;
  private List<NotDetectedFeatureEntity> notDetectedFeatures;
  private String recommendations;

  private String brand;
  private String product;
  private String videoLanguage;
  private String vertical;
  private String assetName;
  private String shot;
  private String text;
  private String speech;
  private String logo;
  private String objects;
  private String face;
  private String person;
  private String labelName;
  private String explicit;
}
