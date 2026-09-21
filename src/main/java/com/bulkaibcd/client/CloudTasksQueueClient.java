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

import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.OidcToken;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.protobuf.ByteString;
import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A client gateway responsible for encapsulating Cloud Tasks API operations.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CloudTasksQueueClient {

  @Value("${google.cloud.project.id}")
  private String projectId;

  @Value("${google.cloud.location:us-central1}")
  private String location;

  @Value("${google.cloud.tasks.queue:bulkaibcd-queue}")
  private String queueId;

  @Value("${google.cloud.tasks.service-account}")
  private String serviceAccountEmail;

  @Value("${app.backend-url}")
  private String backendUrl;

  private final TaskQueueAdapter taskQueueAdapter;

  /**
   * Enqueues a task to the specified worker endpoint with the given payload.
   *
   * @param endpoint the target endpoint path
   * @param payload the JSON payload to attach to the task body
   * @throws IOException if the task cannot be created
   */
  public void enqueueTask(String endpoint, String payload) throws IOException {
    enqueueTask(endpoint, payload, null, null);
  }

  /**
   * Enqueues a task to the specified worker endpoint with optional task naming suffix and execution delay.
   *
   * @param endpoint the target endpoint path
   * @param payload the JSON payload to attach to the task body
   * @param taskSuffix an optional custom suffix for deduplication
   * @param delaySeconds an optional delay in seconds before task execution
   * @throws IOException if the task cannot be created
   */
  public void enqueueTask(String endpoint, String payload, String taskSuffix, Integer delaySeconds)
      throws IOException {
    String queuePath = QueueName.of(projectId, location, queueId).toString();
    String iapClientId = System.getenv("IAP_CLIENT_ID");

    String cleanBaseUrl = (backendUrl != null) ? backendUrl.trim() : "";
    if (cleanBaseUrl.isEmpty() || !cleanBaseUrl.startsWith("http")) {
      cleanBaseUrl = "https://bulkaibcd-snkjkbyzta-uc.a.run.app";
    }
    if (cleanBaseUrl.endsWith("/")) {
      cleanBaseUrl = cleanBaseUrl.substring(0, cleanBaseUrl.length() - 1);
    }

    String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    String targetUrl = cleanBaseUrl + normalizedEndpoint;

    // When IAP is enabled on Cloud Run, Google IAP requires the OIDC token audience to be IAP_CLIENT_ID.
    // When IAP is not enabled, the audience is cleanBaseUrl.
    String audience =
        (iapClientId != null && !iapClientId.isBlank()) ? iapClientId.trim() : cleanBaseUrl;

    HttpRequest.Builder httpRequestBuilder =
        HttpRequest.newBuilder()
            .setUrl(targetUrl)
            .setHttpMethod(HttpMethod.POST)
            .putHeaders("Content-Type", "application/json")
            .setOidcToken(
                OidcToken.newBuilder()
                    .setServiceAccountEmail(serviceAccountEmail)
                    .setAudience(audience)
                    .build());

    if (payload != null) {
      httpRequestBuilder.setBody(ByteString.copyFrom(payload, StandardCharsets.UTF_8));
    }

    Task.Builder taskBuilder =
        Task.newBuilder()
            .setHttpRequest(httpRequestBuilder.build())
            .setDispatchDeadline(Duration.newBuilder().setSeconds(600).build());

    if (taskSuffix != null && !taskSuffix.isEmpty()) {
      String taskName =
          String.format(
              "projects/%s/locations/%s/queues/%s/tasks/%s",
              projectId, location, queueId, taskSuffix);
      taskBuilder.setName(taskName);
    }

    if (delaySeconds != null && delaySeconds > 0) {
      Instant scheduledTime = Instant.now().plusSeconds(delaySeconds);
      Timestamp timestamp =
          Timestamp.newBuilder()
              .setSeconds(scheduledTime.getEpochSecond())
              .setNanos(scheduledTime.getNano())
              .build();
      taskBuilder.setScheduleTime(timestamp);
    }

    try {
      taskQueueAdapter.createTask(queuePath, taskBuilder.build());
      log.info(
          "CloudTasksQueueClient: Task enqueued to {} with suffix: {}, delay: {}s",
          endpoint,
          taskSuffix,
          delaySeconds);
    } catch (AlreadyExistsException e) {
      log.warn(
          "CloudTasksQueueClient: Task with suffix {} already exists. Skipping duplicate enqueue"
              + " request.",
          taskSuffix);
    }
  }
}
