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
  private String format;
  private String gcsObjectId;
  private String errorMessage;
}
