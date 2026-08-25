package com.bulkaibcd.service.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.GoogleDriveClient;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.test.StepVerifier;

class DriveResolveServiceTest {

  private ObjectProvider<GoogleDriveClient> provider;
  private GoogleDriveClient driveClient;
  private DriveResolveService service;

  @BeforeEach
  void setUp() {
    @SuppressWarnings("unchecked")
    ObjectProvider<GoogleDriveClient> p = mock(ObjectProvider.class);
    provider = p;
    driveClient = mock(GoogleDriveClient.class);
    when(provider.getIfAvailable()).thenReturn(driveClient);
    service = new DriveResolveService(provider);
  }

  @Test
  void executeValidationFailsWhenUrlMissing() {
    StepVerifier.create(service.execute(Map.of()))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              @SuppressWarnings("unchecked")
              Map<String, Object> body = (Map<String, Object>) resp.getBody();
              assertThat(body).containsEntry("error", "url is required");
            })
        .verifyComplete();
  }

  @Test
  void executeReturns503WhenDriveClientUnavailable() {
    when(provider.getIfAvailable()).thenReturn(null);

    StepVerifier.create(service.execute(Map.of("url", "https://drive.google.com/file")))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            })
        .verifyComplete();
  }

  @Test
  void executeResolvesVideosSuccessfully() throws Exception {
    GoogleDriveClient.ResolvedVideo v1 =
        new GoogleDriveClient.ResolvedVideo("id1", "vid1.mp4", "video/mp4", "https://drive.google.com/vid1", "https://thumb.url");
    when(driveClient.resolve("https://drive.google.com/folder")).thenReturn(List.of(v1));

    StepVerifier.create(service.execute(Map.of("url", "https://drive.google.com/folder")))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              @SuppressWarnings("unchecked")
              Map<String, Object> body = (Map<String, Object>) resp.getBody();
              assertThat(body).containsKey("videos");
            })
        .verifyComplete();
  }

  @Test
  void executeMapsIllegalArgumentExceptionTo400() throws Exception {
    when(driveClient.resolve("https://bad.url")).thenThrow(new IllegalArgumentException("Unrecognized URL"));

    StepVerifier.create(service.execute(Map.of("url", "https://bad.url")))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              @SuppressWarnings("unchecked")
              Map<String, Object> body = (Map<String, Object>) resp.getBody();
              assertThat(body).containsEntry("code", "UNRECOGNIZED_URL");
            })
        .verifyComplete();
  }

  @Test
  void executeMapsGenericExceptionTo502() throws Exception {
    when(driveClient.resolve("https://drive.url")).thenThrow(new IOException("Drive backend failed"));

    StepVerifier.create(service.execute(Map.of("url", "https://drive.url")))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
              @SuppressWarnings("unchecked")
              Map<String, Object> body = (Map<String, Object>) resp.getBody();
              assertThat(body).containsEntry("code", "DRIVE_ERROR");
            })
        .verifyComplete();
  }
}
