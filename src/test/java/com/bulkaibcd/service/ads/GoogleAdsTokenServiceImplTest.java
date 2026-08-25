package com.bulkaibcd.service.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.GoogleAdsCredentialsEntity;
import com.bulkaibcd.repository.GoogleAdsCredentialsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GoogleAdsTokenServiceImplTest {

  private GoogleAdsCredentialsRepository repo;
  private ObjectMapper objectMapper;
  private HttpClient httpClient;
  private GoogleAdsTokenServiceImpl service;

  @BeforeEach
  void setUp() {
    repo = mock(GoogleAdsCredentialsRepository.class);
    objectMapper = new ObjectMapper();
    httpClient = mock(HttpClient.class);
    service = new GoogleAdsTokenServiceImpl(repo, objectMapper);
    ReflectionTestUtils.setField(service, "httpClient", httpClient);
  }

  @Test
  void getValidAccessTokenReturnsCachedWhenValid() {
    GoogleAdsCredentialsEntity creds =
        GoogleAdsCredentialsEntity.builder()
            .accessToken("valid-token")
            .refreshToken("refresh-tok")
            .expiresAt(System.currentTimeMillis() + 300000)
            .build();

    when(repo.findById("global")).thenReturn(Mono.just(creds));

    StepVerifier.create(service.getValidAccessToken())
        .expectNext("valid-token")
        .verifyComplete();
  }

  @Test
  void getValidAccessTokenThrowsWhenNotConfigured() {
    when(repo.findById("global")).thenReturn(Mono.empty());

    StepVerifier.create(service.getValidAccessToken())
        .expectErrorMatches(t -> t instanceof IllegalStateException && t.getMessage().contains("not configured"))
        .verify();
  }

  @Test
  void getValidAccessTokenThrowsWhenNoTokens() {
    GoogleAdsCredentialsEntity creds =
        GoogleAdsCredentialsEntity.builder().clientId("cid").build();
    when(repo.findById("global")).thenReturn(Mono.just(creds));

    StepVerifier.create(service.getValidAccessToken())
        .expectErrorMatches(t -> t instanceof IllegalStateException && t.getMessage().contains("OAuth authorization is missing"))
        .verify();
  }

  @Test
  void getValidAccessTokenRefreshesWhenExpired() throws Exception {
    GoogleAdsCredentialsEntity creds =
        GoogleAdsCredentialsEntity.builder()
            .clientId("cid")
            .clientSecret("csec")
            .refreshToken("refresh-tok")
            .accessToken("expired-token")
            .expiresAt(System.currentTimeMillis() - 1000)
            .build();

    when(repo.findById("global")).thenReturn(Mono.just(creds));
    when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"access_token\":\"refreshed-tok\",\"expires_in\":3600}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    StepVerifier.create(service.getValidAccessToken())
        .expectNext("refreshed-tok")
        .verifyComplete();
  }

  @Test
  void exchangeCodeForTokensExchangesAndSavesCredentials() throws Exception {
    GoogleAdsCredentialsEntity creds =
        GoogleAdsCredentialsEntity.builder()
            .clientId("cid")
            .clientSecret("csec")
            .build();

    when(repo.findById("global")).thenReturn(Mono.just(creds));
    when(repo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"access_token\":\"new-tok\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    StepVerifier.create(service.exchangeCodeForTokens("code-123", "https://redirect.uri"))
        .assertNext(
            saved -> {
              assertThat(saved.getAccessToken()).isEqualTo("new-tok");
              assertThat(saved.getRefreshToken()).isEqualTo("new-refresh");
              assertThat(saved.getExpiresAt()).isGreaterThan(System.currentTimeMillis());
            })
        .verifyComplete();
  }
}
