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
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A request payload to initiate batch upload of unlisted YouTube videos to Google Cloud Storage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadUnlistedVideosRequest {

  @JsonProperty("request_id")
  @JsonAlias("requestId")
  private String requestId;

  @JsonProperty("unlisted_youtube_video_ids")
  @JsonAlias("unlistedYoutubeVideoIds")
  private List<String> unlistedYoutubeVideoIds;

  @JsonProperty("gcs_uri_prefix")
  @JsonAlias("gcsUriPrefix")
  private String gcsUriPrefix;

  @JsonProperty("user_id")
  @JsonAlias("userId")
  private String userId;

  @JsonProperty("analysis_name")
  @JsonAlias("analysisName")
  private String analysisName;
}
