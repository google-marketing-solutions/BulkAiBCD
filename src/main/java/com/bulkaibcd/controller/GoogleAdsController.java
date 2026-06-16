package com.bulkaibcd.controller;

import com.bulkaibcd.client.GoogleAdsClient;
import com.bulkaibcd.model.GoogleAdsCampaignDto;
import com.bulkaibcd.model.GoogleAdsConfigDto;
import com.bulkaibcd.model.GoogleAdsStatusDto;
import com.bulkaibcd.model.GoogleAdsVideoAssetDto;
import com.bulkaibcd.service.ads.GoogleAdsConfigService;
import com.bulkaibcd.service.ads.GoogleAdsTokenService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v2/ads")
@RequiredArgsConstructor
@Slf4j
public class GoogleAdsController {

  private final GoogleAdsConfigService configService;
  private final GoogleAdsTokenService tokenService;
  private final GoogleAdsClient googleAdsClient;

  @GetMapping("/status")
  public Mono<GoogleAdsStatusDto> getStatus() {
    return configService.getStatus();
  }

  @PostMapping("/configure")
  public Mono<ResponseEntity<Map<String, String>>> configure(
      @RequestBody GoogleAdsConfigDto request,
      HttpServletRequest servletRequest) {

    if (request.getClientId() == null || request.getClientId().isBlank()
        || request.getClientSecret() == null || request.getClientSecret().isBlank()
        || request.getDeveloperToken() == null || request.getDeveloperToken().isBlank()) {
      return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "All fields are required")));
    }

    String redirectUri = buildRedirectUri(servletRequest);

    return configService.configureAndGetAuthUrl(request, redirectUri)
        .map(authUrl -> ResponseEntity.ok(Map.of("authorizationUrl", authUrl)));
  }

  @GetMapping("/callback")
  public Mono<Void> callback(
      @RequestParam("code") String code,
      @RequestParam(value = "state", required = false) String state,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse) {

    String redirectUri = buildRedirectUri(servletRequest);
    String frontendRedirectUrl = (state != null && !state.isBlank()) ? state : "http://localhost:4200";

    return tokenService.exchangeCodeForTokens(code, redirectUri)
        .doOnSuccess(saved -> {
          try {
            servletResponse.sendRedirect(frontendRedirectUrl + "/new?adsConfigured=true");
          } catch (IOException e) {
            log.error("Failed to redirect back to frontend", e);
          }
        })
        .doOnError(err -> {
          log.error("Failed to exchange code for tokens", err);
          try {
            servletResponse.sendRedirect(frontendRedirectUrl + "/new?adsError=" + URLEncoder.encode(err.getMessage(), StandardCharsets.UTF_8));
          } catch (IOException e) {
            log.error("Failed to redirect back with error", e);
          }
        })
        .then();
  }

  @GetMapping("/customers")
  public Flux<String> listCustomers() {
    return googleAdsClient.listAccessibleCustomers()
        .onErrorResume(err -> {
          log.error("Error listing customers", err);
          return Flux.error(err);
        });
  }

  @GetMapping("/campaigns")
  public Flux<GoogleAdsCampaignDto> listCampaigns(
      @RequestParam("customerId") String customerId,
      @RequestParam(value = "nameMatch", required = false) String nameMatch,
      @RequestParam(value = "matchType", required = false) String matchType,
      @RequestParam(value = "status", defaultValue = "active") String status) {
    return googleAdsClient.searchCampaigns(customerId, nameMatch, matchType, status)
        .onErrorResume(err -> {
          log.error("Error listing campaigns for customer: " + customerId, err);
          return Flux.error(err);
        });
  }

  @GetMapping("/video-assets")
  public Flux<GoogleAdsVideoAssetDto> listVideoAssets(
      @RequestParam("customerId") String customerId,
      @RequestParam("campaignId") String campaignId) {
    return googleAdsClient.listVideoAssets(customerId, campaignId)
        .onErrorResume(err -> {
          log.error("Error listing video assets for campaign: " + campaignId, err);
          return Flux.error(err);
        });
  }

  private String buildRedirectUri(HttpServletRequest request) {
    String scheme = request.getScheme();
    String serverName = request.getServerName();
    int serverPort = request.getServerPort();
    String contextPath = request.getContextPath();

    StringBuilder redirectUri = new StringBuilder();
    redirectUri.append(scheme).append("://").append(serverName);

    // Only append port if it's not default for the scheme
    if (("http".equals(scheme) && serverPort != 80) || ("https".equals(scheme) && serverPort != 443)) {
      redirectUri.append(":").append(serverPort);
    }

    redirectUri.append(contextPath).append("/api/v2/ads/callback");
    return redirectUri.toString();
  }
}
