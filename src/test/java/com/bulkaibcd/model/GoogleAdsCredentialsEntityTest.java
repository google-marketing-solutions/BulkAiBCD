package com.bulkaibcd.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoogleAdsCredentialsEntityTest {

  @Test
  void testCredentialsEntityBuilderAndGetters() {
    GoogleAdsCredentialsEntity entity =
        GoogleAdsCredentialsEntity.builder()
            .id("global")
            .developerToken("dev-token")
            .clientId("client-id")
            .clientSecret("client-secret")
            .accessToken("access-token")
            .refreshToken("refresh-token")
            .expiresAt(System.currentTimeMillis() + 3600000)
            .build();

    assertThat(entity.getId()).isEqualTo("global");
    assertThat(entity.getDeveloperToken()).isEqualTo("dev-token");
    assertThat(entity.getClientId()).isEqualTo("client-id");
    assertThat(entity.getClientSecret()).isEqualTo("client-secret");
    assertThat(entity.getAccessToken()).isEqualTo("access-token");
    assertThat(entity.getRefreshToken()).isEqualTo("refresh-token");
    assertThat(entity.getExpiresAt()).isNotNull();
  }

  @Test
  void testHasValidCredentials() {
    GoogleAdsCredentialsEntity valid =
        GoogleAdsCredentialsEntity.builder()
            .developerToken("dev")
            .clientId("cid")
            .clientSecret("csec")
            .build();
    assertThat(valid.hasValidCredentials()).isTrue();

    GoogleAdsCredentialsEntity missingClientId =
        GoogleAdsCredentialsEntity.builder()
            .developerToken("dev")
            .clientId("")
            .clientSecret("csec")
            .build();
    assertThat(missingClientId.hasValidCredentials()).isFalse();

    GoogleAdsCredentialsEntity nullField =
        GoogleAdsCredentialsEntity.builder()
            .developerToken(null)
            .clientId("cid")
            .clientSecret("csec")
            .build();
    assertThat(nullField.hasValidCredentials()).isFalse();
  }

  @Test
  void testHasTokens() {
    GoogleAdsCredentialsEntity hasTokens =
        GoogleAdsCredentialsEntity.builder().refreshToken("refresh-tok").build();
    assertThat(hasTokens.hasTokens()).isTrue();

    GoogleAdsCredentialsEntity noTokens =
        GoogleAdsCredentialsEntity.builder().refreshToken(null).build();
    assertThat(noTokens.hasTokens()).isFalse();

    GoogleAdsCredentialsEntity blankTokens =
        GoogleAdsCredentialsEntity.builder().refreshToken("   ").build();
    assertThat(blankTokens.hasTokens()).isFalse();
  }

  @Test
  void testIsAccessTokenExpired() {
    long now = System.currentTimeMillis();

    GoogleAdsCredentialsEntity nullToken =
        GoogleAdsCredentialsEntity.builder().accessToken(null).expiresAt(now + 100000).build();
    assertThat(nullToken.isAccessTokenExpired()).isTrue();

    GoogleAdsCredentialsEntity nullExpiresAt =
        GoogleAdsCredentialsEntity.builder().accessToken("token").expiresAt(null).build();
    assertThat(nullExpiresAt.isAccessTokenExpired()).isTrue();

    GoogleAdsCredentialsEntity expired =
        GoogleAdsCredentialsEntity.builder().accessToken("token").expiresAt(now + 30000).build();
    assertThat(expired.isAccessTokenExpired()).isTrue();

    GoogleAdsCredentialsEntity valid =
        GoogleAdsCredentialsEntity.builder().accessToken("token").expiresAt(now + 120000).build();
    assertThat(valid.isAccessTokenExpired()).isFalse();
  }
}

