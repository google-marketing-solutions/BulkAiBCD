package com.bulkaibcd.service.output;

import com.bulkaibcd.model.GenerateReportRequest;
import com.bulkaibcd.client.GoogleSheetsClient;
import com.bulkaibcd.service.analysis.ApiService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Service responsible for generating a Google Sheet report directly in the user's Google Drive.
 *
 * <p>Validates OAuth header tokens, verifies GoogleSpreadsheetService availability, and executes
 * spreadsheet generation asynchronously via bounded elastic threads.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GenerateSpreadsheetReportService
    implements ApiService<GenerateReportRequest, ResponseEntity<Map<String, String>>> {

  private final ObjectProvider<GoogleSheetsClient> spreadsheetServiceProvider;

  @Override
  public Mono<ResponseEntity<Map<String, String>>> execute(GenerateReportRequest request) {
    String analysisId = request.getAnalysisId();
    String userAccessToken = request.getUserAccessToken();
    String legacyAccessToken = request.getLegacyAccessToken();

    String token =
        (userAccessToken != null && !userAccessToken.isBlank())
            ? userAccessToken
            : legacyAccessToken;
    if (token == null || token.isBlank()) {
      return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "missing_token")));
    }

    GoogleSheetsClient svc = spreadsheetServiceProvider.getIfAvailable();
    if (svc == null) {
      return Mono.just(
          ResponseEntity.status(503).body(Map.of("error", "sheets_service_unavailable")));
    }

    log.info(
        "GenerateSpreadsheetReportService: Generating spreadsheet report for analysisId: {}",
        analysisId);

    return Mono.fromCallable(() -> svc.generateSheetInUserDrive(analysisId, token))
        .subscribeOn(Schedulers.boundedElastic())
        .map(url -> ResponseEntity.ok(Map.of("sheetUrl", url)))
        .onErrorResume(
            err -> {
              log.error(
                  "GenerateSpreadsheetReportService: Spreadsheet report failed for {}",
                  analysisId,
                  err);
              return Mono.just(
                  ResponseEntity.status(502)
                      .body(
                          Map.of(
                              "error", err.getMessage() == null ? "unknown" : err.getMessage())));
            });
  }
}
