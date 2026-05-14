package com.bulkaibcd.service;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.OidcToken;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class CloudTasksService {

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

  public void enqueueTask(String endpoint, String payload) throws IOException {
    try (CloudTasksClient client = CloudTasksClient.create()) {
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

      Task task = Task.newBuilder().setHttpRequest(httpRequestBuilder.build()).build();

      client.createTask(queuePath, task);
      log.info("Task enqueued to {}", endpoint);
    }
  }
}
