package com.bulkaibcd.service.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.GoogleSheetsClient;
import com.bulkaibcd.model.GenerateReportRequest;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;

class GenerateSpreadsheetReportServiceTest {

  private ObjectProvider<GoogleSheetsClient> sheetsProvider;
  private GoogleSheetsClient sheetsClient;
  private GenerateSpreadsheetReportService service;

  @BeforeEach
  void setUp() {
    @SuppressWarnings("unchecked")
    ObjectProvider<GoogleSheetsClient> provider = mock(ObjectProvider.class);
    sheetsProvider = provider;
    sheetsClient = mock(GoogleSheetsClient.class);
    when(sheetsProvider.getIfAvailable()).thenReturn(sheetsClient);

    service = new GenerateSpreadsheetReportService(sheetsProvider);
  }

  @Test
  void executeValidationFailsWhenTokenMissing() {
    GenerateReportRequest req = GenerateReportRequest.builder().analysisId("ana-1").build();

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(resp.getBody()).containsEntry("error", "missing_token");
            })
        .verifyComplete();
  }

  @Test
  void executeValidationFailsWhenServiceUnavailable() {
    when(sheetsProvider.getIfAvailable()).thenReturn(null);
    GenerateReportRequest req =
        GenerateReportRequest.builder().analysisId("ana-1").userAccessToken("tok").build();

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(resp.getBody()).containsEntry("error", "sheets_service_unavailable");
            })
        .verifyComplete();
  }

  @Test
  void executeGeneratesReportSuccessfully() throws Exception {
    GenerateReportRequest req =
        GenerateReportRequest.builder().analysisId("ana-1").userAccessToken("tok").build();

    when(sheetsClient.generateSheetInUserDrive("ana-1", "tok"))
        .thenReturn("https://docs.google.com/spreadsheets/d/sheet-123");

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).containsEntry("sheetUrl", "https://docs.google.com/spreadsheets/d/sheet-123");
            })
        .verifyComplete();
  }

  @Test
  void executeMapsExceptionTo502() throws Exception {
    GenerateReportRequest req =
        GenerateReportRequest.builder().analysisId("ana-1").userAccessToken("tok").build();

    when(sheetsClient.generateSheetInUserDrive("ana-1", "tok"))
        .thenThrow(new IOException("Sheets API write error"));

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
              assertThat(resp.getBody()).containsEntry("error", "Sheets API write error");
            })
        .verifyComplete();
  }
}
