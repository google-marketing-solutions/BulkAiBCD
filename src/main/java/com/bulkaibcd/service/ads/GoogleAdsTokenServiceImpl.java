package com.bulkaibcd.service.ads;

import com.bulkaibcd.model.GoogleAdsCredentialsEntity;
import com.bulkaibcd.repository.GoogleAdsCredentialsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@Slf4j
@RequiredArgsConstructor
public class GoogleAdsTokenServiceImpl implements GoogleAdsTokenService {

  private final GoogleAdsCredentialsRepository credentialsRepository;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient = HttpClient.newBuilder().build();

  private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";

  @Override
  public Mono<String> getValidAccessToken() {
    return credentialsRepository.findById("global")
        .switchIfEmpty(Mono.error(new IllegalStateException("Google Ads is not configured. Please complete setup in the UI.")))
        .flatMap(creds -> {
          if (!creds.hasTokens()) {
            return Mono.error(new IllegalStateException("OAuth authorization is missing. Please authorize Google Ads in the UI."));
          }
          if (creds.isAccessTokenExpired()) {
            log.info("GoogleAdsTokenService: Access token is expired or missing. Refreshing token...");
            return refreshAccessToken(creds);
          }
          return Mono.just(creds.getAccessToken());
        });
  }

  private Mono<String> refreshAccessToken(GoogleAdsCredentialsEntity creds) {
    return Mono.fromCallable(() -> {
      String requestBody = buildFormEncodedBody(Map.of(
          "client_id", creds.getClientId(),
          "client_secret", creds.getClientSecret(),
          "refresh_token", creds.getRefreshToken(),
          "grant_type", "refresh_token"
      ));

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(TOKEN_URL))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(requestBody))
          .build();

      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.error("GoogleAdsTokenService: Token refresh failed. Status: {}, Body: {}", response.statusCode(), response.body());
        throw new IllegalStateException("Failed to refresh access token: " + response.body());
      }

      JsonNode json = objectMapper.readTree(response.body());
      String newAccessToken = json.get("access_token").asText();
      long expiresInSeconds = json.get("expires_in").asLong();

      creds.setAccessToken(newAccessToken);
      creds.setExpiresAt(System.currentTimeMillis() + (expiresInSeconds * 1000));
      return creds;
    })
    .subscribeOn(Schedulers.boundedElastic())
    .flatMap(credentialsRepository::save)
    .map(GoogleAdsCredentialsEntity::getAccessToken);
  }

  @Override
  public Mono<GoogleAdsCredentialsEntity> exchangeCodeForTokens(String code, String redirectUri) {
    return credentialsRepository.findById("global")
        .switchIfEmpty(Mono.error(new IllegalStateException("Google Ads credentials are not configured yet.")))
        .flatMap(creds -> Mono.fromCallable(() -> {
          String requestBody = buildFormEncodedBody(Map.of(
              "client_id", creds.getClientId(),
              "client_secret", creds.getClientSecret(),
              "code", code,
              "grant_type", "authorization_code",
              "redirect_uri", redirectUri
          ));

          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(TOKEN_URL))
              .header("Content-Type", "application/x-www-form-urlencoded")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

          HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() != 200) {
            log.error("GoogleAdsTokenService: Code exchange failed. Status: {}, Body: {}", response.statusCode(), response.body());
            throw new IllegalStateException("OAuth exchange failed: " + response.body());
          }

          JsonNode json = objectMapper.readTree(response.body());
          creds.setAccessToken(json.get("access_token").asText());
          if (json.has("refresh_token")) {
            creds.setRefreshToken(json.get("refresh_token").asText());
          }
          long expiresInSeconds = json.get("expires_in").asLong();
          creds.setExpiresAt(System.currentTimeMillis() + (expiresInSeconds * 1000));
          return creds;
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(credentialsRepository::save));
  }

  private String buildFormEncodedBody(Map<String, String> params) {
    return params.entrySet().stream()
        .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
        .reduce((p1, p2) -> p1 + "&" + p2)
        .orElse("");
  }
}
