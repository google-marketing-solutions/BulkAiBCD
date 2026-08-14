/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
  private List<String> customFeaturesLong;
  private List<String> customFeaturesShort;
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
    /** The format for this specific video (e.g., LONG, SHORT) */
    private String format;
  }
}
