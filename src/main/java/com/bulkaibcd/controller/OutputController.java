package com.bulkaibcd.controller;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.GoogleSlidesService;
import com.bulkaibcd.service.GoogleSlidesService.PitchDeckParams;
import com.bulkaibcd.service.GoogleSpreadsheetService;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v2/output")
@RequiredArgsConstructor
@Slf4j
public class OutputController {

  private static final String USER_TOKEN_HEADER = "X-Google-Access-Token";
  private static final int MAX_PARALLEL_DECKS = 4;

  /**
   * Static checklist of feature bullets present in {@code master_pitch_deck.pptx}
   * (extracted from slide 5 — the full detailed-results layout). Each entry here
   * matches the literal text after the bullet character in the template; when
   * {@code VideoMetadataEntity.features} contains one of these, the pitch-deck
   * service flips that line's {@code ○} to {@code ●}.
   *
   * <p>Keep this list in sync with the template. If the template's bullet text
   * is edited, regenerate by dumping the slide's paragraphs and re-extracting.
   */
  private static final List<String> ALL_FEATURES =
      List.of(
          "(A) Starting Strong",
          "(B) Brand Visual (3+ Times)",
          "(C) Product Context",
          "(C) Clear Messaging",
          "(C) Single Message",
          "(C) Casual Language",
          "(C) Expression of Benefit",
          "(C) Competitive Claim",
          "(C) Visualization of Benefit",
          "(C) Emotions",
          "(C) Humor",
          "(C) Delight",
          "(D) Call-to-Action (Text)",
          "(D) Call-to-Action (Speech)",
          "(D) Relevant Call-to-Action",
          "(D) Purchase Incentive (Limited Time/Quantities)",
          "(D) Special Offer (Text)",
          "(D) Special Offer (Speech)",
          "(D) Price (Text)",
          "(D) Price (Speech)",
          "(D) Power of Free (Text)",
          "(D) Power of Free (Speech)",
          "(D) Path to Purchase",
          "(D) Search Bar",
          "(B) Brand Logo (Large)",
          "(B) Brand Mention (Speech)",
          "(B) Brand Mention (Speech) (See & Say)",
          "(B) Brand Mention (Speech) (Last 5s)",
          "(B) Brand Mention (Speech) (First 5s)",
          "(B) Brand Mention (Speech) (See & Say) (First 5s)",
          "(B) Brand Mnemonic",
          "(B) Multiple Brand Elements",
          "(B) Product Visual",
          "(B) Product Visual (First 5s)",
          "(B) Product Visual (Last 5s)",
          "(B) Product Visual (Close-up)",
          "(B) Product Visual (Extreme Close-up)",
          "(B) Product Mention (Speech or Text)",
          "(B) Product Mention (Speech)",
          "(B) Product Mention (Speech) (First 5s)",
          "(B) Product Mention (Speech) (Last 5s)",
          "(B) Product Mention (Text)",
          "(B) Product Focus",
          "(C) Presence of People",
          "(C) Presence of People (First 5s)",
          "(C) Presence of People (Close-up)",
          "(C) Visible Face (First 5s)",
          "(C) Product Interaction",
          "(A) Quick Pacing",
          "(A) Quick Pacing (First 5s)",
          "(A) Tight Framing",
          "(A) Tight Framing (First 5s)",
          "(A) Sound On",
          "(A) Music",
          "(A) Sound Effects",
          "(A) Voice",
          "(A) Voice-Over",
          "(A) Dialogue (1-Person)",
          "(A) Dialogue (2+ People)",
          "(A) Direct to Camera",
          "(A) Supers",
          "(A) Supers with Audio",
          "(A) Supers with Audio (Augmented)",
          "(A) Supers with Audio (See & Say)",
          "(A) Large Supers",
          "(A) Bright Visuals",
          "(A) High Contrast Visuals",
          "(B) Brand Visual",
          "(B) Brand Visual (First 5s)",
          "(B) Brand Visual (Last 5s)",
          "(B) Brand Visual (Overlaid)",
          "(B) Brand Visual (In-situation)");

  private static final DateTimeFormatter DATE_FMT =
      DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneOffset.UTC);

  private final VideoMetadataRepository videoMetadataRepository;
  private final AnalysisRequestRepository analysisRequestRepository;
  private final ObjectProvider<GoogleSlidesService> slidesServiceProvider;
  private final ObjectProvider<GoogleSpreadsheetService> spreadsheetServiceProvider;

  @GetMapping("/videos/{analysisId}")
  public Flux<VideoMetadataEntity> getVideos(@PathVariable String analysisId) {
    return videoMetadataRepository.findByAnalysisId(analysisId);
  }

  /**
   * Generates one Google Slides pitch deck per selected video, in parallel (bounded
   * concurrency = {@link #MAX_PARALLEL_DECKS}), using the committed PPTX template +
   * {@code batchUpdate} token replacements. Files land in the caller's own Drive.
   *
   * <p>Request body: {@code {"videoIds": ["<videoId>", ...]}}. Empty / missing list → 400.
   * Request header: {@code X-Google-Access-Token} (Firebase-Auth {@code drive.file} token).
   *
   * <p>Response: {@code {"decks": [{"videoId": ..., "videoTitle": ..., "deckUrl": ...}, ...]}}.
   */
  @PostMapping("/generate-deck/{analysisId}")
  public Mono<ResponseEntity<Map<String, Object>>> generateDeck(
      @PathVariable String analysisId,
      @RequestHeader(name = USER_TOKEN_HEADER, required = false) String userAccessToken,
      @RequestBody(required = false) Map<String, Object> payload) {
    if (userAccessToken == null || userAccessToken.isBlank()) {
      return Mono.just(
          ResponseEntity.badRequest().body(Map.of("error", "missing_token")));
    }
    GoogleSlidesService svc = slidesServiceProvider.getIfAvailable();
    if (svc == null) {
      return Mono.just(
          ResponseEntity.status(503).body(Map.of("error", "slides_service_unavailable")));
    }

    @SuppressWarnings("unchecked")
    List<String> videoIds =
        payload != null && payload.get("videoIds") instanceof List<?>
            ? (List<String>) payload.get("videoIds")
            : List.of();
    if (videoIds.isEmpty()) {
      return Mono.just(
          ResponseEntity.badRequest().body(Map.of("error", "videoIds_required")));
    }

    Mono<AnalysisRequestEntity> analysisMono =
        analysisRequestRepository
            .findById(analysisId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("analysis_not_found")));

    return analysisMono.flatMap(
        analysis ->
            Flux.fromIterable(videoIds)
                .flatMapSequential(
                    videoId -> loadVideo(analysisId, videoId),
                    MAX_PARALLEL_DECKS)
                .flatMap(
                    video ->
                        Mono.fromCallable(
                                () -> {
                                  String videoTitle = resolveVideoTitle(video);
                                  int adherence = avg(video);
                                  PitchDeckParams params =
                                      PitchDeckParams.builder()
                                          .userAccessToken(userAccessToken)
                                          .brandName(analysis.getBrandName())
                                          .videoTitle(videoTitle)
                                          .videoUrl(video.getVideoUrl())
                                          .adherenceScore(adherence)
                                          .completionDate(formatCompletionDate(analysis))
                                          .marketingObjective(
                                              humanizeObjective(analysis.getMarketingObjective()))
                                          .allFeatures(ALL_FEATURES)
                                          .detectedFeatures(
                                              video.getFeatures() == null
                                                  ? List.of()
                                                  : video.getFeatures())
                                          .build();
                                  String url = svc.generatePitchDeckForVideo(params);
                                  return Map.<String, Object>of(
                                      "videoId", video.getVideoId(),
                                      "videoTitle", videoTitle,
                                      "deckUrl", url);
                                })
                            .subscribeOn(Schedulers.boundedElastic()),
                    MAX_PARALLEL_DECKS)
                .collectList()
                .<ResponseEntity<Map<String, Object>>>map(
                    decks -> ResponseEntity.ok(Map.of("decks", new ArrayList<>(decks))))
                .onErrorResume(
                    err -> {
                      log.error("Pitch-deck generation failed for {}", analysisId, err);
                      return Mono.just(
                          ResponseEntity.status(502)
                              .body(
                                  Map.of(
                                      "error",
                                      err.getMessage() == null ? "unknown" : err.getMessage())));
                    }));
  }

  /**
   * Generates one Google Sheet in the caller's Drive from the analysis's video metadata.
   *
   * <p>Request header: {@code X-Google-Access-Token}. Response: {@code {"sheetUrl": ...}}.
   */
  @PostMapping("/report/{analysisId}")
  public Mono<ResponseEntity<Map<String, String>>> generateSpreadsheetReport(
      @PathVariable String analysisId,
      @RequestHeader(name = USER_TOKEN_HEADER, required = false) String userAccessToken) {
    if (userAccessToken == null || userAccessToken.isBlank()) {
      return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "missing_token")));
    }
    GoogleSpreadsheetService svc = spreadsheetServiceProvider.getIfAvailable();
    if (svc == null) {
      return Mono.just(
          ResponseEntity.status(503).body(Map.of("error", "sheets_service_unavailable")));
    }
    return Mono.fromCallable(() -> svc.generateSheetInUserDrive(analysisId, userAccessToken))
        .subscribeOn(Schedulers.boundedElastic())
        .map(url -> ResponseEntity.ok(Map.of("sheetUrl", url)))
        .onErrorResume(
            err -> {
              log.error("Spreadsheet report failed for {}", analysisId, err);
              return Mono.just(
                  ResponseEntity.status(502)
                      .body(
                          Map.of(
                              "error",
                              err.getMessage() == null ? "unknown" : err.getMessage())));
            });
  }

  private Mono<VideoMetadataEntity> loadVideo(String analysisId, String videoId) {
    String docId = analysisId + "_" + videoId;
    return videoMetadataRepository
        .findById(docId)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("video_not_found: " + videoId)));
  }

  private static String resolveVideoTitle(VideoMetadataEntity videoMetadata) {
    if (videoMetadata.getAssetName() != null && !videoMetadata.getAssetName().isBlank()) return videoMetadata.getAssetName();
    if (videoMetadata.getVideoName() != null && !videoMetadata.getVideoName().isBlank()) return videoMetadata.getVideoName();
    return videoMetadata.getVideoId();
  }

  private static int avg(VideoMetadataEntity v) {
    int sum = 0;
    int n = 0;
    Integer[] scores = {v.getAScore(), v.getBScore(), v.getCScore(), v.getDScore()};
    for (Integer s : scores) {
      if (s != null) {
        sum += s;
        n++;
      }
    }
    return n == 0 ? 0 : Math.round((float) sum / n);
  }

  private static String formatCompletionDate(AnalysisRequestEntity a) {
    if (a == null) return "";
    var ts = a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt();
    if (ts == null) return "";
    return DATE_FMT.format(java.time.Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()));
  }

  private static String humanizeObjective(String raw) {
    if (raw == null || raw.isBlank()) return "";
    return switch (raw) {
      case "core_unknown" -> "Core/Unknown";
      case "awareness" -> "Awareness";
      case "consideration" -> "Consideration";
      case "conversion" -> "Conversion";
      case "brand_building" -> "Brand Building";
      default -> raw;
    };
  }
}
