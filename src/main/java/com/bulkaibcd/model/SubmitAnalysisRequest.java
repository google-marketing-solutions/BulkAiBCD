package com.bulkaibcd.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitAnalysisRequest {
  private String requesterId;
  private String analysisName;
  private String analysisType;
  private String brandName;
  private String marketingObjective;
  private List<VideoInput> videos;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class VideoInput {
    /** "youtube" | "drive" | "file" | "id" */
    private String sourceType;
    /** Human-readable display label (YT title, filename, Ads ID). */
    private String videoName;
    /** Canonical URL — what Vertex AI fetches + the "View Video" link renders. */
    private String videoUrl;
    /** For sourceType=file only: path within the GCS uploads bucket (no gs:// prefix). */
    private String gcsObjectId;
    /** Optional client-captured thumbnail (JPEG data URL) for uploaded files. */
    private String thumbnailUrl;
  }
}
