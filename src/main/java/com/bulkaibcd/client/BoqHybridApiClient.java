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

import com.bulkaibcd.model.UploadStatusRequest;
import com.bulkaibcd.model.UploadStatusResponse;
import com.bulkaibcd.model.UploadUnlistedVideosRequest;
import com.bulkaibcd.model.UploadUnlistedVideosResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.hybrid.connect.c2pauthorizer.client.HelheimTokenRefresher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A client implementation for invoking Boq InputService RPCs through the Google Hybrid API gateway.
 */
@Component
@Slf4j
public class BoqHybridApiClient implements BoqInputServiceClient {

  private static final String UPLOAD_RPC_PATH = "/bulkaibcd.InputService/UploadUnlistedVideosToGcs";
  private static final String STATUS_RPC_PATH = "/bulkaibcd.InputService/GetUploadStatus";
  private static final String HEADER_AUTHORIZATION = "Authorization";
  private static final String HEADER_CONTENT_TYPE = "Content-Type";
  private static final String MEDIA_TYPE_JSON = "application/json";

  private final String baseUrl;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final GoogleCredentials credentials;
  private final HelheimTokenRefresher helheimTokenRefresher;
  private final Supplier<String> tokenSupplier;

  public BoqHybridApiClient(
      @Value("${app.boq.hybrid-api-url:https://autopush-bulkaibcd.hybrid.sandbox.googleapis.com}")
          String baseUrl,
      ObjectMapper objectMapper,
      HttpClient boqHttpClient,
      @Autowired(required = false) HelheimTokenRefresher helheimTokenRefresher) {
    this(baseUrl, objectMapper, boqHttpClient, initCredentials(), helheimTokenRefresher, null);
  }

  BoqHybridApiClient(
      String baseUrl,
      ObjectMapper objectMapper,
      HttpClient httpClient,
      GoogleCredentials credentials,
      Supplier<String> tokenSupplier) {
    this(baseUrl, objectMapper, httpClient, credentials, null, tokenSupplier);
  }

  BoqHybridApiClient(
      String baseUrl,
      ObjectMapper objectMapper,
      HttpClient httpClient,
      GoogleCredentials credentials,
      HelheimTokenRefresher helheimTokenRefresher,
      Supplier<String> tokenSupplier) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.credentials = credentials;
    this.helheimTokenRefresher = helheimTokenRefresher;
    this.tokenSupplier = tokenSupplier;
  }

  @Override
  public UploadUnlistedVideosResponse uploadUnlistedVideosToGcs(
      UploadUnlistedVideosRequest request) {
    log.info(
        "BoqHybridApiClient: Initiating UploadUnlistedVideosToGcs for {} video(s) (Analysis: '{}', User: '{}', Prefix: '{}', Video IDs: {})",
        request.getUnlistedYoutubeVideoIds() != null ? request.getUnlistedYoutubeVideoIds().size() : 0,
        request.getAnalysisName(),
        request.getUserId(),
        request.getGcsUriPrefix(),
        request.getUnlistedYoutubeVideoIds());
    try {
      String requestJson = objectMapper.writeValueAsString(request);
      String responseBody = sendRpcRequest(UPLOAD_RPC_PATH, requestJson);
      UploadUnlistedVideosResponse response =
          objectMapper.readValue(responseBody, UploadUnlistedVideosResponse.class);
      log.info(
          "BoqHybridApiClient: UploadUnlistedVideosToGcs succeeded. Assigned batch requestId: {}",
          response.getRequestId());
      return response;
    } catch (IOException | InterruptedException e) {
      log.error("BoqHybridApiClient: Failed to upload unlisted videos to GCS", e);
      throw new IllegalStateException("Failed to call UploadUnlistedVideosToGcs RPC", e);
    }
  }

  @Override
  public UploadStatusResponse getUploadStatus(String requestId) {
    log.info("BoqHybridApiClient: Polling GetUploadStatus for batch requestId: {}", requestId);
    try {
      UploadStatusRequest statusRequest =
          UploadStatusRequest.builder().requestId(requestId).build();
      String requestJson = objectMapper.writeValueAsString(statusRequest);
      String responseBody = sendRpcRequest(STATUS_RPC_PATH, requestJson);
      UploadStatusResponse response =
          objectMapper.readValue(responseBody, UploadStatusResponse.class);
      log.info(
          "BoqHybridApiClient: GetUploadStatus for requestId: {} -> allCompleted: {}, completed: {}/{}, inProgress: {}, failed: {}",
          requestId,
          response.isAllCompleted(),
          response.getCompletedCount(),
          response.getTotalCount(),
          response.getInProgressCount(),
          response.getFailedCount());
      return response;
    } catch (IOException | InterruptedException e) {
      log.error("BoqHybridApiClient: Failed to get upload status for request ID: {}", requestId, e);
      throw new IllegalStateException("Failed to call GetUploadStatus RPC", e);
    }
  }

  private String sendRpcRequest(String rpcPath, String requestBodyJson)
      throws IOException, InterruptedException {
    String endpointUrl = baseUrl + rpcPath;
    String accessToken = fetchAccessToken();
    String helheimToken = resolveHelheimToken();

    log.info(
        "BoqHybridApiClient: Dispatching RPC to {} [Auth Token: {}, Helheim Token: {}]",
        endpointUrl,
        accessToken != null ? "PRESENT" : "MISSING",
        helheimToken != null ? "PRESENT" : "MISSING");
    log.debug("BoqHybridApiClient: Request Payload: {}", requestBodyJson);

    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(endpointUrl))
            .timeout(Duration.ofSeconds(60))
            .header(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON)
            .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson));

    if (accessToken != null && !accessToken.isBlank()) {
      requestBuilder.header(HEADER_AUTHORIZATION, "Bearer " + accessToken);
    }

    if (helheimToken != null && !helheimToken.isBlank()) {
      requestBuilder.header(HelheimTokenRefresher.getHelheimTokenHeader(), helheimToken);
    }

    Instant start = Instant.now();
    HttpRequest request = requestBuilder.build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    long durationMs = java.time.Duration.between(start, Instant.now()).toMillis();

    log.info(
        "BoqHybridApiClient: Received HTTP {} from {} ({} ms)",
        response.statusCode(),
        endpointUrl,
        durationMs);
    log.debug("BoqHybridApiClient: Response Payload: {}", response.body());

    if (response.statusCode() != 200) {
      log.error(
          "BoqHybridApiClient: RPC to {} failed with HTTP {}. Response Body: {}",
          endpointUrl,
          response.statusCode(),
          response.body());
      throw new IllegalStateException(
          String.format("Boq RPC failed with HTTP %d: %s", response.statusCode(), response.body()));
    }

    return response.body();
  }

  private String fetchAccessToken() {
    if (credentials == null) {
      return null;
    }
    try {
      credentials.refreshIfExpired();
      if (credentials.getAccessToken() != null) {
        return credentials.getAccessToken().getTokenValue();
      }
    } catch (IOException e) {
      log.warn("BoqHybridApiClient: Could not refresh Google ADC access token", e);
    }
    return null;
  }

  private static GoogleCredentials initCredentials() {
    try {
      return GoogleCredentials.getApplicationDefault()
          .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
    } catch (IOException e) {
      log.warn("BoqHybridApiClient: Could not obtain Google Application Default Credentials", e);
      return null;
    }
  }

  private String resolveHelheimToken() {
    if (tokenSupplier != null) {
      String token = tokenSupplier.get();
      if (token != null && !token.isBlank()) {
        return token;
      }
    }
    if (helheimTokenRefresher != null) {
      try {
        String token = helheimTokenRefresher.getHelheimToken().get();
        if (token != null && !token.isBlank()) {
          return token;
        }
      } catch (Exception e) {
        log.warn("BoqHybridApiClient: Could not retrieve dynamic Helheim token from refresher", e);
      }
    }
    String token = System.getenv("HELHEIM_TOKEN");
    if (token != null && !token.isBlank()) {
      return token;
    }
    return System.getProperty("helheim.token");
  }
}
