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

package com.bulkaibcd.service.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.YouTubeVideoInfoDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class YouTubeResolveServiceTest {

  private HttpClient httpClient;
  private ObjectMapper objectMapper;
  private YouTubeResolveService service;

  private boolean unlistedSupported = false;


  @BeforeEach
  void setUp() {
    httpClient = mock(HttpClient.class);
    objectMapper = new ObjectMapper();
    service = new YouTubeResolveService(objectMapper, httpClient);
  }

  @Test
  void extractVideoIdExtractsFromStandardWatchUrl() {
    String id = YouTubeResolveService.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    assertThat(id).isEqualTo("dQw4w9WgXcQ");
  }

  @Test
  void extractVideoIdExtractsFromShortsUrl() {
    String id = YouTubeResolveService.extractVideoId("https://youtube.com/shorts/abc12345678");
    assertThat(id).isEqualTo("abc12345678");
  }

  @Test
  void extractVideoIdExtractsFromYoutuBeUrl() {
    String id = YouTubeResolveService.extractVideoId("https://youtu.be/xyz98765432");
    assertThat(id).isEqualTo("xyz98765432");
  }

  @Test
  void extractVideoIdExtractsFromRawId() {
    String id = YouTubeResolveService.extractVideoId("dQw4w9WgXcQ");
    assertThat(id).isEqualTo("dQw4w9WgXcQ");
  }

  @Test
  void extractVideoIdReturnsNullForInvalid() {
    String id = YouTubeResolveService.extractVideoId("not-a-valid-youtube-url");
    assertThat(id).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolveVideosDetectsUnlistedFromWatchPage() throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenReturn("<html><head><title>My Unlisted Video - YouTube</title></head><body>{\"isUnlisted\":true}</body></html>");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    StepVerifier.create(service.resolveVideos(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ")))
        .assertNext(
            info -> {
              assertThat(info.getVideoId()).isEqualTo("dQw4w9WgXcQ");
              assertThat(info.getTitle()).isEqualTo("My Unlisted Video");
              assertThat(info.isUnlisted()).isTrue();
              if (unlistedSupported) {
                assertThat(info.getErrorMessage()).isNull();
              } else {
                assertThat(info.getErrorMessage()).isEqualTo("Unlisted YouTube videos are not supported.");
              }
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolveVideosDetectsPublicVideo() throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenReturn("<html><head><title>Public Video - YouTube</title></head><body>{\"isUnlisted\":false}</body></html>");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    StepVerifier.create(service.resolveVideos(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ")))
        .assertNext(
            info -> {
              assertThat(info.getVideoId()).isEqualTo("dQw4w9WgXcQ");
              assertThat(info.getTitle()).isEqualTo("Public Video");
              assertThat(info.isUnlisted()).isFalse();
              assertThat(info.getErrorMessage()).isNull();
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolveVideosDetectsUnlistedFromPlayerApi() throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenReturn("{\"videoDetails\":{\"title\":\"Innertube Unlisted Video\"},\"microformat\":{\"playerMicroformatRenderer\":{\"isUnlisted\":true}}}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    StepVerifier.create(service.resolveVideos(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ")))
        .assertNext(
            info -> {
              assertThat(info.getVideoId()).isEqualTo("dQw4w9WgXcQ");
              assertThat(info.getTitle()).isEqualTo("Innertube Unlisted Video");
              assertThat(info.isUnlisted()).isTrue();
              if (unlistedSupported) {
                assertThat(info.getErrorMessage()).isNull();
              } else {
                assertThat(info.getErrorMessage()).isEqualTo("Unlisted YouTube videos are not supported.");
              }
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolveVideosDetectsPublicFromPlayerApi() throws Exception {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenReturn("{\"videoDetails\":{\"title\":\"Innertube Public Video\"},\"microformat\":{\"playerMicroformatRenderer\":{\"isUnlisted\":false}}}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    StepVerifier.create(service.resolveVideos(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ")))
        .assertNext(
            info -> {
              assertThat(info.getVideoId()).isEqualTo("dQw4w9WgXcQ");
              assertThat(info.getTitle()).isEqualTo("Innertube Public Video");
              assertThat(info.isUnlisted()).isFalse();
            })
        .verifyComplete();
  }
}
