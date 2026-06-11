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

/** Client gateway responsible for encapsulating Cloud Tasks API operations. */
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

  public void enqueueTask(String endpoint, String payload) throws IOException {
    enqueueTask(endpoint, payload, null, null);
  }

  public void enqueueTask(String endpoint, String payload, String taskSuffix, Integer delaySeconds)
      throws IOException {
    String queuePath = QueueName.of(projectId, location, queueId).toString();

    HttpRequest.Builder httpRequestBuilder =
        HttpRequest.newBuilder()
            .setUrl(backendUrl + endpoint)
            .setHttpMethod(HttpMethod.POST)
            .putHeaders("Content-Type", "application/json")
            .setOidcToken(
                OidcToken.newBuilder().setServiceAccountEmail(serviceAccountEmail).build());

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
