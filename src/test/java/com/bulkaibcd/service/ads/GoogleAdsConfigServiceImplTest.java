package com.bulkaibcd.service.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.GoogleAdsConfigDto;
import com.bulkaibcd.model.GoogleAdsCredentialsEntity;
import com.bulkaibcd.model.GoogleAdsStatusDto;
import com.bulkaibcd.repository.GoogleAdsCredentialsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GoogleAdsConfigServiceImplTest {

  private GoogleAdsCredentialsRepository repo;
  private GoogleAdsConfigServiceImpl service;

  @BeforeEach
  void setUp() {
    repo = mock(GoogleAdsCredentialsRepository.class);
    service = new GoogleAdsConfigServiceImpl(repo);
  }

  @Test
  void getStatusWhenEntityExistsAndConfigured() {
    GoogleAdsCredentialsEntity entity =
        GoogleAdsCredentialsEntity.builder()
            .clientId("cid")
            .clientSecret("csec")
            .developerToken("dev")
            .refreshToken("tok")
            .build();
    when(repo.findById("global")).thenReturn(Mono.just(entity));

    StepVerifier.create(service.getStatus())
        .assertNext(
            status -> {
              assertThat(status.isConfigured()).isTrue();
              assertThat(status.isAuthorized()).isTrue();
            })
        .verifyComplete();
  }

  @Test
  void getStatusWhenEmptyReturnsFalse() {
    when(repo.findById("global")).thenReturn(Mono.empty());

    StepVerifier.create(service.getStatus())
        .assertNext(
            status -> {
              assertThat(status.isConfigured()).isFalse();
              assertThat(status.isAuthorized()).isFalse();
            })
        .verifyComplete();
  }

  @Test
  void configureAndGetAuthUrlSavesEntityAndReturnsAuthUrl() {
    GoogleAdsConfigDto dto =
        GoogleAdsConfigDto.builder()
            .clientId("client-id-123")
            .clientSecret("secret-456")
            .developerToken("dev-token-789")
            .frontendOrigin("http://localhost:4200")
            .build();

    when(repo.save(any(GoogleAdsCredentialsEntity.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.configureAndGetAuthUrl(dto, "http://localhost:8080/callback"))
        .assertNext(
            url -> {
              assertThat(url).contains("https://accounts.google.com/o/oauth2/v2/auth");
              assertThat(url).contains("client_id=client-id-123");
              assertThat(url).contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fcallback");
              assertThat(url).contains("state=http%3A%2F%2Flocalhost%3A4200");
            })
        .verifyComplete();

    ArgumentCaptor<GoogleAdsCredentialsEntity> captor =
        ArgumentCaptor.forClass(GoogleAdsCredentialsEntity.class);
    verify(repo).save(captor.capture());
    assertThat(captor.getValue().getId()).isEqualTo("global");
    assertThat(captor.getValue().getClientId()).isEqualTo("client-id-123");
    assertThat(captor.getValue().getClientSecret()).isEqualTo("secret-456");
    assertThat(captor.getValue().getDeveloperToken()).isEqualTo("dev-token-789");
  }

  @Test
  void configureAndGetAuthUrlWithNullOrigin() {
    GoogleAdsConfigDto dto =
        GoogleAdsConfigDto.builder()
            .clientId("cid")
            .clientSecret("csec")
            .developerToken("dev")
            .frontendOrigin(null)
            .build();

    when(repo.save(any(GoogleAdsCredentialsEntity.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.configureAndGetAuthUrl(dto, "http://localhost:8080/callback"))
        .assertNext(
            url -> {
              assertThat(url).contains("state=");
            })
        .verifyComplete();
  }
}
