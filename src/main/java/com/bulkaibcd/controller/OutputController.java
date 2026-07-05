package com.bulkaibcd.controller;

import com.bulkaibcd.client.GcsClient;
import com.bulkaibcd.model.GenerateDeckRequest;
import com.bulkaibcd.model.GenerateReportRequest;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.output.GenerateDeckService;
import com.bulkaibcd.service.output.GenerateSpreadsheetReportService;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage.SignUrlOption;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v2/output")
@RequiredArgsConstructor
@Slf4j
public class OutputController {

  private static final String USER_TOKEN_HEADER = "X-Google-Access-Token";

  private final VideoMetadataRepository videoMetadataRepository;
  private final GenerateDeckService generateDeckService;
  private final GenerateSpreadsheetReportService generateSpreadsheetReportService;
  private final GcsClient gcsClient;

  @GetMapping("/videos/{analysisId}")
  public Flux<VideoMetadataEntity> getVideos(@PathVariable String analysisId) {
    return videoMetadataRepository.findByAnalysisId(analysisId)
        .map(v -> {
          // Strip out massive raw visual tracking JSON strings to prevent UI tab OOM
          v.setShot(null);
          v.setText(null);
          v.setSpeech(null);
          v.setLogo(null);
          v.setObjects(null);
          v.setFace(null);
          v.setPerson(null);
          v.setLabelName(null);
          v.setExplicit(null);

          if (v.getGcsObjectId() != null && !v.getGcsObjectId().isBlank()) {
            int slash = v.getGcsObjectId().indexOf('/');
            if (slash > 0) {
              String bucket = v.getGcsObjectId().substring(0, slash);
              String object = v.getGcsObjectId().substring(slash + 1);
              try {
                BlobInfo blobInfo = BlobInfo.newBuilder(bucket, object).build();
                URL signed = gcsClient.signUrl(
                    blobInfo,
                    60,
                    TimeUnit.MINUTES,
                    SignUrlOption.httpMethod(HttpMethod.GET),
                    SignUrlOption.withV4Signature());
                v.setSignedUrl(signed.toString());
              } catch (Exception e) {
                log.warn("Failed to generate signed URL for {}", v.getGcsObjectId(), e);
              }
            }
          }

          return v;
        });
  }

  @PostMapping("/generate-deck/{analysisId}")
  public Mono<ResponseEntity<Map<String, Object>>> generateDeck(
      @PathVariable String analysisId,
      @RequestHeader(name = USER_TOKEN_HEADER, required = false) String userAccessToken,
      @RequestHeader(name = "X-Drive-Access-Token", required = false) String legacyAccessToken,
      @RequestBody(required = false) Map<String, Object> payload) {
    @SuppressWarnings("unchecked")
    List<String> videoIds =
        payload != null && payload.get("videoIds") instanceof List<?>
            ? (List<String>) payload.get("videoIds")
            : null;

    return generateDeckService.execute(
        GenerateDeckRequest.builder()
            .analysisId(analysisId)
            .userAccessToken(userAccessToken)
            .legacyAccessToken(legacyAccessToken)
            .videoIds(videoIds)
            .build());
  }

  /**
   * Generates one Google Sheet in the caller's Drive from the analysis's video metadata.
   *
   * <p>Request header: {@code X-Google-Access-Token}. Response: {@code {"sheetUrl": ...}}.
   */
  @PostMapping("/report/{analysisId}")
  public Mono<ResponseEntity<Map<String, String>>> generateSpreadsheetReport(
      @PathVariable String analysisId,
      @RequestHeader(name = USER_TOKEN_HEADER, required = false) String userAccessToken,
      @RequestHeader(name = "X-Drive-Access-Token", required = false) String legacyAccessToken) {
    return generateSpreadsheetReportService.execute(
        GenerateReportRequest.builder()
            .analysisId(analysisId)
            .userAccessToken(userAccessToken)
            .legacyAccessToken(legacyAccessToken)
            .build());
  }


}
