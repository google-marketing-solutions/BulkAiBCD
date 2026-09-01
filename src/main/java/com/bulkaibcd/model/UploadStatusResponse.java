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
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A response payload from Boq InputService GetUploadStatus RPC describing aggregate and per-video upload status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UploadStatusResponse {

  @JsonProperty("request_id")
  @JsonAlias("requestId")
  private String requestId;

  @JsonProperty("total_count")
  @JsonAlias("totalCount")
  private int totalCount;

  @JsonProperty("completed_count")
  @JsonAlias("completedCount")
  private int completedCount;

  @JsonProperty("failed_count")
  @JsonAlias("failedCount")
  private int failedCount;

  @JsonProperty("in_progress_count")
  @JsonAlias("inProgressCount")
  private int inProgressCount;

  @JsonProperty("all_completed")
  @JsonAlias("allCompleted")
  private boolean allCompleted;

  @JsonProperty("video_upload_statuses")
  @JsonAlias({"videos", "videoUploadStatuses", "video_upload_status_infos", "videoUploadStatusInfos", "videoSources", "video_sources"})
  private List<VideoUploadStatusInfo> videoUploadStatuses;
}
