package com.bulkaibcd.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.GoogleAdsCampaignDto;
import com.bulkaibcd.model.GoogleAdsCredentialsEntity;
import com.bulkaibcd.model.GoogleAdsVideoAssetDto;
import com.bulkaibcd.repository.GoogleAdsCredentialsRepository;
import com.bulkaibcd.service.ads.GoogleAdsTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GoogleAdsRestClientTest {

  private GoogleAdsTokenService tokenService;
  private GoogleAdsCredentialsRepository credentialsRepo;
  private ObjectMapper objectMapper;
  private HttpClient httpClient;
  private GoogleAdsRestClient client;

  @BeforeEach
  void setUp() {
    tokenService = mock(GoogleAdsTokenService.class);
    credentialsRepo = mock(GoogleAdsCredentialsRepository.class);
    objectMapper = new ObjectMapper();
    httpClient = mock(HttpClient.class);

    client = new GoogleAdsRestClient(tokenService, credentialsRepo, objectMapper);
    ReflectionTestUtils.setField(client, "httpClient", httpClient);
  }

  @Test
  void listAccessibleCustomersParsesResourceNames() throws Exception {
    when(tokenService.getValidAccessToken()).thenReturn(Mono.just("valid-tok"));
    GoogleAdsCredentialsEntity creds =
        GoogleAdsCredentialsEntity.builder().developerToken("dev-tok").build();
    when(credentialsRepo.findById("global")).thenReturn(Mono.just(creds));

    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"resourceNames\":[\"customers/123456\",\"customers/789012\"]}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    StepVerifier.create(client.listAccessibleCustomers())
        .expectNext("123456")
        .expectNext("789012")
        .verifyComplete();
  }

  @Test
  void searchCampaignsParsesResults() throws Exception {
    when(tokenService.getValidAccessToken()).thenReturn(Mono.just("valid-tok"));
    GoogleAdsCredentialsEntity creds =
        GoogleAdsCredentialsEntity.builder().developerToken("dev-tok").build();
    when(credentialsRepo.findById("global")).thenReturn(Mono.just(creds));

    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("{\"results\":[{\"campaign\":{\"id\":\"111\",\"name\":\"Summer Promo\",\"status\":\"ENABLED\"}}]}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    StepVerifier.create(client.searchCampaigns("123456", "Summer", "contains", "active"))
        .assertNext(
            c -> {
              assertThat(c.getId()).isEqualTo("111");
              assertThat(c.getName()).isEqualTo("Summer Promo");
              assertThat(c.getStatus()).isEqualTo("ENABLED");
            })
        .verifyComplete();
  }

  @Test
  void listVideoAssetsParsesResponsiveVideoAds() throws Exception {
    when(tokenService.getValidAccessToken()).thenReturn(Mono.just("valid-tok"));
    GoogleAdsCredentialsEntity creds =
        GoogleAdsCredentialsEntity.builder().developerToken("dev-tok").build();
    when(credentialsRepo.findById("global")).thenReturn(Mono.just(creds));

    String json =
        "{\"results\":[{\"adGroupAd\":{\"ad\":{\"id\":\"222\",\"name\":\"Ad 1\",\"responsiveVideoAd\":{\"videos\":[{\"youtubeVideoId\":\"yt-abc\"}]}}}}]}";
    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(json);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    StepVerifier.create(client.listVideoAssets("123456", "camp-1"))
        .assertNext(
            v -> {
              assertThat(v.getId()).isEqualTo("222");
              assertThat(v.getName()).isEqualTo("Ad 1");
              assertThat(v.getYoutubeVideoId()).isEqualTo("yt-abc");
            })
        .verifyComplete();
  }

  @Test
  void apiErrorThrowsIllegalStateException() throws Exception {
    when(tokenService.getValidAccessToken()).thenReturn(Mono.just("valid-tok"));
    GoogleAdsCredentialsEntity creds =
        GoogleAdsCredentialsEntity.builder().developerToken("dev-tok").build();
    when(credentialsRepo.findById("global")).thenReturn(Mono.just(creds));

    @SuppressWarnings("unchecked")
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(400);
    when(response.body()).thenReturn("{\"error\":\"INVALID_CUSTOMER_ID\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    StepVerifier.create(client.listAccessibleCustomers())
        .expectErrorMatches(t -> t instanceof IllegalStateException && t.getMessage().contains("INVALID_CUSTOMER_ID"))
        .verify();
  }
}
