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
import org.springframework.beans.factory.annotation.Autowired;
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
  private static final Pattern UNLISTED_PATTERN =
      Pattern.compile(
          "\"isUnlisted\"\\s*:\\s*true|\"isCrawlable\"\\s*:\\s*false|PRIVACY_UNLISTED|\"privacyStatus\"\\s*:\\s*\"UNLISTED\"|<meta[^>]*itemprop=[\"']unlisted[\"'][^>]*content=[\"']true[\"']|<meta[^>]*name=[\"']robots[\"'][^>]*content=[\"']noindex[\"']",
          Pattern.CASE_INSENSITIVE);

  private static final String INNERTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player";
  private static final String OEMBED_URL_TEMPLATE =
      "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=%s&format=json";
  private static final String WATCH_URL_TEMPLATE = "https://www.youtube.com/watch?v=%s";
  private static final String USER_AGENT_HEADER =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
  private static final String GOOGLEBOT_USER_AGENT =
      "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";
  private static final String CONSENT_COOKIE =
      "SOCS=CAESEwgDEgk2OTg5ODk4OTQaAmVuIAEaBgiA_LyaBg; CONSENT=YES+cb.20210328-17-p0.en+FX+410; PREF=hl=en";
  private static final int RESOLVE_CONCURRENCY = 6;

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;

  @Autowired
  public YouTubeResolveService(ObjectMapper objectMapper) {
    this(
        objectMapper,
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
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
                    .subscribeOn(Schedulers.boundedElastic()),
            RESOLVE_CONCURRENCY);
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

  private record PlayerApiResult(String title, Boolean isUnlisted) {}

  private record WatchPageResult(String title, boolean isUnlisted) {}

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
    Boolean isUnlisted = null;

    // 1. Try Innertube Player API (fast, lightweight ~6KB JSON)
    PlayerApiResult playerResult = fetchFromPlayerApi(videoId);
    if (playerResult != null) {
      if (playerResult.title() != null && !playerResult.title().isBlank()) {
        title = playerResult.title();
      }
      if (playerResult.isUnlisted() != null) {
        isUnlisted = playerResult.isUnlisted();
      }
    }

    // 2. If privacy status could not be determined from Player API (or was marked false/public), inspect watch page HTML
    if (isUnlisted == null || !isUnlisted) {
      WatchPageResult watchResult = inspectWatchPage(videoId, canonicalUrl);
      if (watchResult != null) {
        isUnlisted = watchResult.isUnlisted();
        if (title == null && watchResult.title() != null && !watchResult.title().isBlank()) {
          title = watchResult.title();
        }
      }
    }

    // 3. If title is still missing, fallback to oEmbed
    if (title == null || title.isBlank() || "- YouTube".equals(title) || "YouTube".equals(title)) {
      title = fetchTitleFromOEmbed(videoId);
    }

    if (title == null || title.isBlank()) {
      title = canonicalUrl;
    }

    boolean finalUnlisted = Boolean.TRUE.equals(isUnlisted);

    log.info(
        "YouTubeResolveService: Resolved video ID: {} -> Title: '{}', Unlisted: {}",
        videoId,
        title,
        finalUnlisted);

    return YouTubeVideoInfoDto.builder()
        .videoId(videoId)
        .url(canonicalUrl)
        .title(title)
        .unlisted(finalUnlisted)
        .build();
  }

  private static final String ANDROID_TESTSUITE_USER_AGENT =
      "GooglePlayServices/23.44.14 (040400-580126424)";

  private PlayerApiResult fetchFromPlayerApi(String videoId) {
    // 1. Try ANDROID_TESTSUITE client (works reliably from datacenter IPs without web botguard)
    PlayerApiResult androidResult =
        fetchFromPlayerApiClient(videoId, "ANDROID_TESTSUITE", "1.9", ANDROID_TESTSUITE_USER_AGENT, null);
    if (androidResult != null && androidResult.title() != null && androidResult.isUnlisted() != null) {
      return androidResult;
    }

    // 2. Fallback to WEB client if needed
    PlayerApiResult webResult =
        fetchFromPlayerApiClient(videoId, "WEB", "2.20240101.00.00", USER_AGENT_HEADER, CONSENT_COOKIE);
    if (webResult != null && (webResult.title() != null || webResult.isUnlisted() != null)) {
      return webResult;
    }

    return androidResult;
  }

  private PlayerApiResult fetchFromPlayerApiClient(
      String videoId, String clientName, String clientVersion, String userAgent, String cookie) {
    try {
      String payload =
          String.format(
              "{\"context\":{\"client\":{\"clientName\":\"%s\",\"clientVersion\":\"%s\"}},\"videoId\":\"%s\"}",
              clientName,
              clientVersion,
              videoId);

      HttpRequest.Builder requestBuilder =
          HttpRequest.newBuilder()
              .uri(URI.create(INNERTUBE_PLAYER_URL))
              .header("Content-Type", "application/json")
              .header("User-Agent", userAgent)
              .timeout(Duration.ofSeconds(6))
              .POST(HttpRequest.BodyPublishers.ofString(payload));

      if (cookie != null) {
        requestBuilder.header("Cookie", cookie);
        requestBuilder.header("X-YouTube-Client-Name", "1");
        requestBuilder.header("X-YouTube-Client-Version", clientVersion);
      }

      HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        JsonNode root = objectMapper.readTree(response.body());
        String title = null;
        Boolean isUnlisted = null;

        if (root.has("videoDetails")) {
          JsonNode videoDetails = root.get("videoDetails");
          if (videoDetails.has("title")) {
            title = videoDetails.get("title").asText();
          }
          if (videoDetails.has("isCrawlable")) {
            boolean isCrawlable = videoDetails.get("isCrawlable").asBoolean();
            isUnlisted = !isCrawlable;
          }
        }

        if (root.has("microformat") && root.get("microformat").has("playerMicroformatRenderer")) {
          JsonNode microformat = root.get("microformat").get("playerMicroformatRenderer");
          if (microformat.has("isUnlisted")) {
            isUnlisted = microformat.get("isUnlisted").asBoolean();
          }
          if (title == null && microformat.has("title") && microformat.get("title").has("simpleText")) {
            title = microformat.get("title").get("simpleText").asText();
          }
        }

        log.info(
            "YouTubeResolveService: Player API ({}) response for video ID {}: HTTP {}, title='{}', isUnlisted={}",
            clientName,
            videoId,
            response.statusCode(),
            title,
            isUnlisted);

        if (title != null || isUnlisted != null) {
          return new PlayerApiResult(title, isUnlisted);
        }
      } else {
        log.warn("YouTubeResolveService: Player API ({}) returned HTTP {} for video ID: {}", clientName, response.statusCode(), videoId);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("YouTubeResolveService: Interrupted during player API ({}) lookup for video ID: {}", clientName, videoId, e);
    } catch (Exception e) {
      log.warn("YouTubeResolveService: Player API ({}) lookup failed for video ID: {}, falling back: {}", clientName, videoId, e.getMessage());
    }
    return null;
  }

  private WatchPageResult inspectWatchPage(String videoId, String canonicalUrl) {
    try {
      HttpRequest watchRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(canonicalUrl))
              .header("User-Agent", GOOGLEBOT_USER_AGENT)
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Cookie", CONSENT_COOKIE)
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build();

      HttpResponse<String> watchResponse =
          httpClient.send(watchRequest, HttpResponse.BodyHandlers.ofString());

      if (watchResponse.statusCode() == 200) {
        String body = watchResponse.body();
        boolean isUnlisted = UNLISTED_PATTERN.matcher(body).find();
        String title = null;

        Matcher titleMatcher = TITLE_TAG_PATTERN.matcher(body);
        if (titleMatcher.find()) {
          String rawTitle = titleMatcher.group(1).trim();
          String cleaned = cleanHtmlEntities(rawTitle.replaceFirst("\\s*-\\s*YouTube$", ""));
          if (!cleaned.isBlank() && !"- YouTube".equals(cleaned) && !"YouTube".equals(cleaned)) {
            title = cleaned;
          }
        }
        return new WatchPageResult(title, isUnlisted);
      } else {
        log.warn("YouTubeResolveService: Watch page lookup returned HTTP {} for video ID: {}", watchResponse.statusCode(), videoId);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("YouTubeResolveService: Interrupted inspecting watch page for video ID: {}", videoId, e);
    } catch (IOException e) {
      log.warn("YouTubeResolveService: Failed to inspect watch page for video ID: {}", videoId, e);
    }
    return null;
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
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("YouTubeResolveService: Interrupted fetching oEmbed title for video ID: {}", videoId, e);
    } catch (IOException e) {
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
