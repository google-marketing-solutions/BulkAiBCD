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

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A status record for an individual video's upload progress in a batch job.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VideoUploadStatusInfo {

  @JsonProperty("video_id")
  @JsonAlias("videoId")
  private String videoId;

  @JsonProperty("status")
  @JsonAlias({"status", "uploadStatus", "upload_status"})
  private String status;

  @JsonProperty("gcs_path")
  @JsonAlias({"gcsPath", "gcs_path", "unlistedYoutubeUrl", "unlisted_youtube_url"})
  private String gcsPath;

  @JsonProperty("error_message")
  @JsonAlias({"errorMessage", "error_message"})
  private String errorMessage;
}
