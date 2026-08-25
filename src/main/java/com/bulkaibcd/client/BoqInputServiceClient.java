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

package com.bulkaibcd.client;

import com.bulkaibcd.model.UploadStatusResponse;
import com.bulkaibcd.model.UploadUnlistedVideosRequest;
import com.bulkaibcd.model.UploadUnlistedVideosResponse;

/**
 * A client interface for interacting with internal Boq InputService RPCs via Hybrid API.
 */
public interface BoqInputServiceClient {

  /**
   * Initiates an asynchronous batch upload of unlisted YouTube videos to Google Cloud Storage.
   *
   * @param request the batch upload request containing unlisted video IDs and GCS URI prefix
   * @return the response containing the batch request ID
   */
  UploadUnlistedVideosResponse uploadUnlistedVideosToGcs(UploadUnlistedVideosRequest request);

  /**
   * Fetches the current status of a batch upload job.
   *
   * @param requestId the batch request ID returned by {@link #uploadUnlistedVideosToGcs}
   * @return the aggregated and per-video upload status response
   */
  UploadStatusResponse getUploadStatus(String requestId);
}
