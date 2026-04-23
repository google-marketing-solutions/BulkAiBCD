package com.bulkaibcd.model;

import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.spring.data.firestore.Document;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document(collectionName = "video_inputs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoInputEntity {
  @DocumentId private String id;
  private String analysisId;
  private String videoId;
  /** Human-readable display label (YT title, original filename, Ads ID label). */
  private String videoName;
  /** Canonical source URL for this video (YT watch URL, Drive URL). Null for file uploads. */
  private String videoUrl;
  /** Client-captured JPEG data URL for uploaded local files; null for URL sources. */
  private String thumbnailUrl;
  private String sourceType;
  private String gcsObjectId;
  private String errorMessage;
}
