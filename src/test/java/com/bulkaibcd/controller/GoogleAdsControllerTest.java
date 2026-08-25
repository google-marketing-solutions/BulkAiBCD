package com.bulkaibcd.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.GoogleAdsClient;
import com.bulkaibcd.model.GoogleAdsCampaignDto;
import com.bulkaibcd.model.GoogleAdsConfigDto;
import com.bulkaibcd.model.GoogleAdsCredentialsEntity;
import com.bulkaibcd.model.GoogleAdsStatusDto;
import com.bulkaibcd.model.GoogleAdsVideoAssetDto;
import com.bulkaibcd.service.ads.GoogleAdsConfigService;
import com.bulkaibcd.service.ads.GoogleAdsTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GoogleAdsControllerTest {

  private GoogleAdsConfigService configService;
  private GoogleAdsTokenService tokenService;
  private GoogleAdsClient googleAdsClient;
  private GoogleAdsController controller;

  @BeforeEach
  void setUp() {
    configService = mock(GoogleAdsConfigService.class);
    tokenService = mock(GoogleAdsTokenService.class);
    googleAdsClient = mock(GoogleAdsClient.class);
    controller = new GoogleAdsController(configService, tokenService, googleAdsClient);
  }

  @Test
  void getStatusDelegatesToConfigService() {
    GoogleAdsStatusDto status = GoogleAdsStatusDto.builder().configured(true).authorized(true).build();
    when(configService.getStatus()).thenReturn(Mono.just(status));

    StepVerifier.create(controller.getStatus())
        .expectNext(status)
        .verifyComplete();
  }

  @Test
  void configureValidationFailureWhenFieldsMissing() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    GoogleAdsConfigDto dto = GoogleAdsConfigDto.builder().clientId("").build();

    StepVerifier.create(controller.configure(dto, req))
        .assertNext(resp -> {
          assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
          assertThat(resp.getBody()).containsEntry("error", "All fields are required");
        })
        .verifyComplete();
  }

  @Test
  void configureSucceedsWithValidFields() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getScheme()).thenReturn("https");
    when(req.getServerName()).thenReturn("example.com");
    when(req.getServerPort()).thenReturn(443);
    when(req.getContextPath()).thenReturn("");

    GoogleAdsConfigDto dto =
        GoogleAdsConfigDto.builder()
            .clientId("cid")
            .clientSecret("csec")
            .developerToken("dev")
            .build();

    when(configService.configureAndGetAuthUrl(eq(dto), eq("https://example.com/api/v2/ads/callback")))
        .thenReturn(Mono.just("https://accounts.google.com/auth-url"));

    StepVerifier.create(controller.configure(dto, req))
        .assertNext(resp -> {
          assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
          assertThat(resp.getBody()).containsEntry("authorizationUrl", "https://accounts.google.com/auth-url");
        })
        .verifyComplete();
  }

  @Test
  void callbackSuccessRedirectsToFrontend() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getScheme()).thenReturn("http");
    when(req.getServerName()).thenReturn("localhost");
    when(req.getServerPort()).thenReturn(8080);
    when(req.getContextPath()).thenReturn("");

    HttpServletResponse resp = mock(HttpServletResponse.class);

    GoogleAdsCredentialsEntity creds = GoogleAdsCredentialsEntity.builder().accessToken("tok").build();
    when(tokenService.exchangeCodeForTokens("code-123", "http://localhost:8080/api/v2/ads/callback"))
        .thenReturn(Mono.just(creds));

    StepVerifier.create(controller.callback("code-123", "http://localhost:4200", req, resp))
        .verifyComplete();

    verify(resp).sendRedirect("http://localhost:4200/new?adsConfigured=true");
  }

  @Test
  void callbackErrorRedirectsWithErrorMessage() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getScheme()).thenReturn("http");
    when(req.getServerName()).thenReturn("localhost");
    when(req.getServerPort()).thenReturn(8080);
    when(req.getContextPath()).thenReturn("");

    HttpServletResponse resp = mock(HttpServletResponse.class);

    when(tokenService.exchangeCodeForTokens("bad-code", "http://localhost:8080/api/v2/ads/callback"))
        .thenReturn(Mono.error(new RuntimeException("invalid_grant")));

    StepVerifier.create(controller.callback("bad-code", "http://localhost:4200", req, resp))
        .expectError(RuntimeException.class)
        .verify();

    verify(resp).sendRedirect("http://localhost:4200/new?adsError=invalid_grant");
  }

  @Test
  void listCustomersCallsClient() {
    when(googleAdsClient.listAccessibleCustomers()).thenReturn(Flux.just("123", "456"));

    StepVerifier.create(controller.listCustomers())
        .expectNext("123")
        .expectNext("456")
        .verifyComplete();
  }

  @Test
  void listCampaignsCallsClient() {
    GoogleAdsCampaignDto c1 = GoogleAdsCampaignDto.builder().id("1").name("Camp 1").build();
    when(googleAdsClient.searchCampaigns("123", "match", "contains", "active"))
        .thenReturn(Flux.just(c1));

    StepVerifier.create(controller.listCampaigns("123", "match", "contains", "active"))
        .expectNext(c1)
        .verifyComplete();
  }

  @Test
  void listVideoAssetsCallsClient() {
    GoogleAdsVideoAssetDto v1 = GoogleAdsVideoAssetDto.builder().id("1").name("Video 1").build();
    when(googleAdsClient.listVideoAssets("123", "camp-1")).thenReturn(Flux.just(v1));

    StepVerifier.create(controller.listVideoAssets("123", "camp-1"))
        .expectNext(v1)
        .verifyComplete();
  }
}
