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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.UploadStatusResponse;
import com.bulkaibcd.model.UploadUnlistedVideosRequest;
import com.bulkaibcd.model.UploadUnlistedVideosResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BoqHybridApiClientTest {

  private HttpClient httpClient;
  private ObjectMapper objectMapper;
  private GoogleCredentials credentials;
  private BoqHybridApiClient client;

  @BeforeEach
  void setUp() {
    httpClient = mock(HttpClient.class);
    objectMapper = new ObjectMapper();
    credentials =
        GoogleCredentials.create(
            new AccessToken("test-adc-token", Date.from(Instant.now().plusSeconds(3600))));
    client =
        new BoqHybridApiClient(
            "https://autopush-bulkaibcd.hybrid.sandbox.googleapis.com",
            objectMapper,
            httpClient,
            credentials,
            () -> "test-helheim-token");
  }

  @Test
  @SuppressWarnings("unchecked")
  void uploadUnlistedVideosToGcsSendsExpectedPayloadAndParsesResponse() throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"request_id\":\"batch-req-123\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    UploadUnlistedVideosRequest request =
        UploadUnlistedVideosRequest.builder()
            .unlistedYoutubeVideoIds(List.of("vid1", "vid2"))
            .gcsUriPrefix("gs://my-bucket/unlisted/")
            .userId("user1")
            .analysisName("test-analysis")
            .build();

    UploadUnlistedVideosResponse result = client.uploadUnlistedVideosToGcs(request);

    assertThat(result).isNotNull();
    assertThat(result.getRequestId()).isEqualTo("batch-req-123");

    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), any());

    HttpRequest captured = requestCaptor.getValue();
    assertThat(captured.uri().toString())
        .isEqualTo("https://autopush-bulkaibcd.hybrid.sandbox.googleapis.com/bulkaibcd.InputService/UploadUnlistedVideosToGcs");
    assertThat(captured.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(captured.headers().firstValue("Authorization")).contains("Bearer test-adc-token");
    assertThat(captured.headers().firstValue("X-Helheim-Token")).contains("test-helheim-token");
    assertThat(captured.timeout()).contains(Duration.ofSeconds(60));
  }

  @Test
  @SuppressWarnings("unchecked")
  void getUploadStatusSendsExpectedPayloadAndParsesResponse() throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    String responseJson =
        "{"
            + "\"total_count\":2,"
            + "\"completed_count\":2,"
            + "\"failed_count\":0,"
            + "\"in_progress_count\":0,"
            + "\"all_completed\":true,"
            + "\"video_upload_statuses\":["
            + "{\"video_id\":\"vid1\",\"status\":\"UPLOAD_COMPLETED\",\"gcs_path\":\"gs://my-bucket/unlisted/vid1.mp4\"}"
            + "]"
            + "}";
    when(response.body()).thenReturn(responseJson);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    UploadStatusResponse result = client.getUploadStatus("batch-req-123");

    assertThat(result).isNotNull();
    assertThat(result.isAllCompleted()).isTrue();
    assertThat(result.getTotalCount()).isEqualTo(2);
    assertThat(result.getVideoUploadStatuses()).hasSize(1);
    assertThat(result.getVideoUploadStatuses().get(0).getVideoId()).isEqualTo("vid1");
    assertThat(result.getVideoUploadStatuses().get(0).getGcsPath())
        .isEqualTo("gs://my-bucket/unlisted/vid1.mp4");
  }

  @Test
  @SuppressWarnings("unchecked")
  void sendRpcRequestThrowsWhenStatusCodeIsNot200() throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(500);
    when(response.body()).thenReturn("Internal server error");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    UploadUnlistedVideosRequest request =
        UploadUnlistedVideosRequest.builder()
            .unlistedYoutubeVideoIds(List.of("vid1"))
            .build();

    assertThatThrownBy(() -> client.uploadUnlistedVideosToGcs(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("500");
  }

  @Test
  @SuppressWarnings("unchecked")
  void sendRpcRequestThrowsWhenIoExceptionOccurs() throws Exception {
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new IOException("Connection reset"));

    assertThatThrownBy(() -> client.getUploadStatus("batch-123"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GetUploadStatus");
  }
}
