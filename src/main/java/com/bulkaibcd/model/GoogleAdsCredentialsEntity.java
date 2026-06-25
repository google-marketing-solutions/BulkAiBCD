package com.bulkaibcd.model;

import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.spring.data.firestore.Document;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document(collectionName = "google_ads_credentials")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoogleAdsCredentialsEntity {
  @DocumentId private String id; // Will always be "global"
  private String clientId;
  private String clientSecret;
  private String developerToken;
  private String accessToken;
  private String refreshToken;
  private Long expiresAt;

  /**
   * Checks if the credentials are fully configured.
   */
  public boolean hasValidCredentials() {
    return clientId != null && !clientId.isBlank()
        && clientSecret != null && !clientSecret.isBlank()
        && developerToken != null && !developerToken.isBlank();
  }

  /**
   * Checks if the OAuth authorization tokens exist.
   */
  public boolean hasTokens() {
    return refreshToken != null && !refreshToken.isBlank();
  }

  /**
   * Checks if the access token is expired or about to expire within a safety window.
   */
  public boolean isAccessTokenExpired() {
    if (accessToken == null || expiresAt == null) {
      return true;
    }
    long now = System.currentTimeMillis();
    // Safety buffer of 60 seconds
    return now + 60000 >= expiresAt;
  }
}
