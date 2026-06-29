package com.bulkaibcd.service.output;

import com.bulkaibcd.enums.MarketingObjective;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.FeatureParameter;
import com.bulkaibcd.model.GenerateDeckRequest;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.client.GoogleSlidesClient;
import com.bulkaibcd.client.GoogleSlidesClient.PitchDeckParams;
import com.bulkaibcd.service.FeatureConfigService;
import com.bulkaibcd.service.analysis.ApiService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Service responsible for generating Google Slides pitch decks per selected video in parallel.
 *
 * <p>Uses the committed PPTX template + batchUpdate token replacements. Files land directly in the
 * caller's own Google Drive.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GenerateDeckService
    implements ApiService<GenerateDeckRequest, ResponseEntity<Map<String, Object>>> {

  private final VideoMetadataRepository videoMetadataRepository;
  private final AnalysisRequestRepository analysisRequestRepository;
  private final ObjectProvider<GoogleSlidesClient> slidesServiceProvider;
  private final FeatureConfigService featureConfigService;

  private static final DateTimeFormatter DATE_FMT =
      DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneOffset.UTC);


  @Override
  public Mono<ResponseEntity<Map<String, Object>>> execute(GenerateDeckRequest request) {
    String analysisId = request.getAnalysisId();
    String userAccessToken = request.getUserAccessToken();
    String legacyAccessToken = request.getLegacyAccessToken();
    List<String> videoIds = request.getVideoIds() != null ? request.getVideoIds() : List.of();

    String token =
        (userAccessToken != null && !userAccessToken.isBlank())
            ? userAccessToken
            : legacyAccessToken;
    if (token == null || token.isBlank()) {
      return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "missing_token")));
    }

    GoogleSlidesClient svc = slidesServiceProvider.getIfAvailable();
    if (svc == null) {
      return Mono.just(
          ResponseEntity.status(503).body(Map.of("error", "slides_service_unavailable")));
    }

    if (videoIds.isEmpty()) {
      return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "videoIds_required")));
    }

    log.info(
        "GenerateDeckService: Generating pitch decks for analysisId: {}, videos: {}",
        analysisId,
        videoIds.size());

    Mono<AnalysisRequestEntity> analysisMono =
        analysisRequestRepository
            .findById(analysisId)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("analysis_not_found")));

    Mono<List<VideoMetadataEntity>> videosMono =
        Flux.fromIterable(videoIds)
            .flatMapSequential(videoId -> loadVideo(analysisId, videoId))
            .collectList();

    return Mono.zip(analysisMono, videosMono)
        .flatMap(
            tuple -> {
              AnalysisRequestEntity analysis = tuple.getT1();
              List<VideoMetadataEntity> videos = tuple.getT2();

              return Mono.fromCallable(
                      () -> {
                        String analysisType = analysis.getAnalysisType();
                        List<FeatureParameter> allFeaturesObj =
                            featureConfigService.getFeaturesByType(analysisType);
                        List<String> featureNames = new ArrayList<>();
                        for (FeatureParameter feature : allFeaturesObj) {
                          featureNames.add(feature.getName());
                        }

                        String marketingObjective =
                            humanizeObjective(analysis.getMarketingObjective());

                        PitchDeckParams params =
                            PitchDeckParams.builder()
                                .userAccessToken(token)
                                .brandName(analysis.getBrandName())
                                .marketingObjective(marketingObjective)
                                .analysisType(analysisType)
                                .allFeatures(featureNames)
                                .videos(videos)
                                .build();

                        String url = svc.generateBulkPitchDeck(params);

                        Map<String, Object> singleBulkDeckObject =
                            Map.of(
                                "videoId", "bulk-report",
                                "videoTitle", "Bulk AiBCD Report",
                                "deckUrl", url);

                        return Map.<String, Object>of("decks", List.of(singleBulkDeckObject));
                      })
                  .subscribeOn(Schedulers.boundedElastic());
            })
        .map(ResponseEntity::ok)
        .onErrorResume(
            err -> {
              log.error(
                  "GenerateDeckService: Bulk Pitch-deck generation failed for {}", analysisId, err);
              return Mono.just(
                  ResponseEntity.status(502)
                      .body(
                          Map.of(
                              "error", err.getMessage() == null ? "unknown" : err.getMessage())));
            });
  }

  private Mono<VideoMetadataEntity> loadVideo(String analysisId, String videoId) {
    String docId = analysisId + "_" + videoId;
    return videoMetadataRepository
        .findById(docId)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("video_not_found: " + videoId)));
  }

  private static String resolveVideoTitle(VideoMetadataEntity v) {
    if (v.getAssetName() != null && !v.getAssetName().isBlank()) return v.getAssetName();
    if (v.getVideoName() != null && !v.getVideoName().isBlank()) return v.getVideoName();
    return v.getVideoId();
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
    return DATE_FMT.format(Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()));
  }

  private static String humanizeObjective(String raw) {
    if (raw == null || raw.isBlank()) return "";
    try {
      MarketingObjective mo = MarketingObjective.valueOf(raw.toUpperCase());
      return switch (mo) {
        case CORE_UNKNOWN -> "Core / Unknown";
        case AWARENESS -> "Awareness";
        case CONSIDERATION -> "Consideration";
        case ACTION -> "Action";
      };
    } catch (IllegalArgumentException e) {
      return raw;
    }
  }
}
