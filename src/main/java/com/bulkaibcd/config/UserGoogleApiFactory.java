package com.bulkaibcd.config;

import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.slides.v1.Slides;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Builds Drive / Slides / Sheets API clients on behalf of an end user, using
 * an OAuth access token forwarded by the frontend (Firebase Auth → drive.file
 * scope). Files created via these clients are owned by the signed-in user
 * rather than the runtime service account — which is the whole point: SAs
 * have no My-Drive storage quota, and users want their decks to show up under
 * "Owned by me" in Drive.
 */
@Component
public class UserGoogleApiFactory {

  private static final String APP_NAME = "bulkaibcd";

  public Drive drive(String accessToken) throws IOException {
    return new Drive.Builder(
            GoogleApiConfig.transport(), GsonFactory.getDefaultInstance(), adapter(accessToken))
        .setApplicationName(APP_NAME)
        .build();
  }

  public Slides slides(String accessToken) throws IOException {
    return new Slides.Builder(
            GoogleApiConfig.transport(), GsonFactory.getDefaultInstance(), adapter(accessToken))
        .setApplicationName(APP_NAME)
        .build();
  }

  public Sheets sheets(String accessToken) throws IOException {
    return new Sheets.Builder(
            GoogleApiConfig.transport(), GsonFactory.getDefaultInstance(), adapter(accessToken))
        .setApplicationName(APP_NAME)
        .build();
  }

  private static HttpCredentialsAdapter adapter(String accessToken) {
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalArgumentException("User access token is required");
    }
    // Expiration null → library treats the token as "still valid"; we rely on
    // the frontend to hand us a freshly-minted token per request. Refresh is
    // owned by Firebase Auth in the browser; the backend is stateless.
    GoogleCredentials creds = GoogleCredentials.create(new AccessToken(accessToken, null));
    return new HttpCredentialsAdapter(creds);
  }
}
