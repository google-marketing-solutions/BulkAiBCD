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

import com.bulkaibcd.model.YouTubeVideoInfoDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A service responsible for extracting YouTube video identifiers, fetching metadata, and detecting unlisted status.
 */
@Service
@Slf4j
public class YouTubeResolveService {

  private static final Pattern YOUTUBE_ID_PATTERN =
      Pattern.compile(
          "(?:https?://)?(?:www\\.)?(?:youtube\\.com/(?:watch\\?v=|embed/|v/|shorts/)|youtu\\.be/)([a-zA-Z0-9_-]{11})|^([a-zA-Z0-9_-]{11})$");
  private static final Pattern TITLE_TAG_PATTERN =
      Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern UNLISTED_JSON_PATTERN =
      Pattern.compile("\"isUnlisted\":\\s*true", Pattern.CASE_INSENSITIVE);
  private static final Pattern UNLISTED_META_PATTERN =
      Pattern.compile("<meta[^>]*itemprop=[\"']unlisted[\"'][^>]*content=[\"']true[\"']", Pattern.CASE_INSENSITIVE);

  private static final String OEMBED_URL_TEMPLATE =
      "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=%s&format=json";
  private static final String WATCH_URL_TEMPLATE = "https://www.youtube.com/watch?v=%s";
  private static final String USER_AGENT_HEADER =
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  public YouTubeResolveService(ObjectMapper objectMapper) {
    this(
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
  }

  YouTubeResolveService(ObjectMapper objectMapper, HttpClient httpClient) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
  }

  /**
   * Resolves a list of YouTube URLs or video IDs into detailed video information records.
   *
   * @param urls the list of YouTube URLs or raw video IDs to resolve
   * @return a reactive {@link Flux} emitting the resolved {@link YouTubeVideoInfoDto} items
   */
  public Flux<YouTubeVideoInfoDto> resolveVideos(List<String> urls) {
    if (urls == null || urls.isEmpty()) {
      return Flux.empty();
    }
    log.info("YouTubeResolveService: Resolving {} YouTube URL(s)...", urls.size());
    return Flux.fromIterable(urls)
        .flatMap(
            url ->
                Mono.fromCallable(() -> resolveSingleVideo(url))
                    .subscribeOn(Schedulers.boundedElastic()));
  }

  /**
   * Extracts the 11-character YouTube video ID from a URL or raw ID string.
   *
   * @param input the URL or video ID string
   * @return the extracted 11-character ID, or {@code null} if no match is found
   */
  public static String extractVideoId(String input) {
    if (input == null || input.isBlank()) {
      return null;
    }
    Matcher matcher = YOUTUBE_ID_PATTERN.matcher(input.trim());
    if (matcher.find()) {
      String idFromUrl = matcher.group(1);
      return idFromUrl != null ? idFromUrl : matcher.group(2);
    }
    return null;
  }

  private YouTubeVideoInfoDto resolveSingleVideo(String rawInput) {
    String videoId = extractVideoId(rawInput);
    if (videoId == null) {
      log.warn("YouTubeResolveService: Could not extract 11-char YouTube ID from input: '{}'", rawInput);
      return YouTubeVideoInfoDto.builder()
          .videoId(rawInput)
          .url(rawInput)
          .title(rawInput)
          .unlisted(false)
          .build();
    }

    String canonicalUrl = String.format(WATCH_URL_TEMPLATE, videoId);
    String title = null;
    boolean isUnlisted = false;

    try {
      HttpRequest watchRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(canonicalUrl))
              .header("User-Agent", USER_AGENT_HEADER)
              .header("Accept-Language", "en-US,en;q=0.9")
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();

      HttpResponse<String> watchResponse =
          httpClient.send(watchRequest, HttpResponse.BodyHandlers.ofString());

      if (watchResponse.statusCode() == 200) {
        String body = watchResponse.body();
        isUnlisted = UNLISTED_JSON_PATTERN.matcher(body).find() || UNLISTED_META_PATTERN.matcher(body).find();

        Matcher titleMatcher = TITLE_TAG_PATTERN.matcher(body);
        if (titleMatcher.find()) {
          String rawTitle = titleMatcher.group(1).trim();
          title = cleanHtmlEntities(rawTitle.replaceFirst(" - YouTube$", ""));
        }
      } else {
        log.warn("YouTubeResolveService: Watch page lookup returned HTTP {} for video ID: {}", watchResponse.statusCode(), videoId);
      }
    } catch (IOException | InterruptedException e) {
      log.warn("YouTubeResolveService: Failed to inspect watch page for video ID: {}", videoId, e);
    }

    if (title == null || title.isBlank()) {
      title = fetchTitleFromOEmbed(videoId);
    }

    if (title == null || title.isBlank()) {
      title = canonicalUrl;
    }

    log.info(
        "YouTubeResolveService: Resolved video ID: {} -> Title: '{}', Unlisted: {}",
        videoId,
        title,
        isUnlisted);

    return YouTubeVideoInfoDto.builder()
        .videoId(videoId)
        .url(canonicalUrl)
        .title(title)
        .unlisted(isUnlisted)
        .build();
  }

  private String fetchTitleFromOEmbed(String videoId) {
    try {
      String oembedUrl = String.format(OEMBED_URL_TEMPLATE, videoId);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(oembedUrl))
              .header("User-Agent", USER_AGENT_HEADER)
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        JsonNode jsonNode = objectMapper.readTree(response.body());
        if (jsonNode.has("title")) {
          return jsonNode.get("title").asText();
        }
      }
    } catch (IOException | InterruptedException e) {
      log.warn("YouTubeResolveService: Failed to fetch oEmbed title for video ID: {}", videoId, e);
    }
    return null;
  }

  private static String cleanHtmlEntities(String text) {
    return text.replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'");
  }
}
