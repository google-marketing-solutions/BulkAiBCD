package com.bulkaibcd.config;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.slides.v1.Slides;
import com.google.api.services.slides.v1.SlidesScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.tasks.v2.CloudTasksClient;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared Drive + Slides API clients. DriveIngestService (folder enumeration,
 * bytes download) and GoogleSlidesService (pitch-deck generation) both need to
 * call Google Workspace APIs with the runtime service account's credentials
 * — those calls require scopes that aren't exposed by the Spring Cloud GCP
 * Storage auto-config, so we build dedicated beans here.
 */
@Configuration
public class GoogleApiConfig {

  private static final String APP_NAME = "bulkaibcd";

  @Bean
  public CloudTasksClient cloudTasksClient() throws IOException {
    return CloudTasksClient.create();
  }

  @Bean
  public Drive driveService() throws IOException {
    return new Drive.Builder(transport(), GsonFactory.getDefaultInstance(), credentials())
        .setApplicationName(APP_NAME)
        .build();
  }

  @Bean
  public Slides slidesService() throws IOException {
    return new Slides.Builder(transport(), GsonFactory.getDefaultInstance(), credentials())
        .setApplicationName(APP_NAME)
        .build();
  }

  @Bean
  public Sheets sheetsService() throws IOException {
    return new Sheets.Builder(transport(), GsonFactory.getDefaultInstance(), credentials())
        .setApplicationName(APP_NAME)
        .build();
  }

  static HttpTransport transport() throws IOException {
    try {
      return GoogleNetHttpTransport.newTrustedTransport();
    } catch (GeneralSecurityException e) {
      throw new IOException("Failed to build trusted HTTP transport", e);
    }
  }

  private static HttpCredentialsAdapter credentials() throws IOException {
    // Drive scope (not just READONLY) is needed because we create / copy decks.
    // Slides PRESENTATIONS covers batchUpdate for text replacement.
    GoogleCredentials creds =
        GoogleCredentials.getApplicationDefault()
            .createScoped(
                List.of(
                    DriveScopes.DRIVE,
                    SlidesScopes.PRESENTATIONS,
                    SheetsScopes.SPREADSHEETS));
    return new HttpCredentialsAdapter(creds);
  }
}
