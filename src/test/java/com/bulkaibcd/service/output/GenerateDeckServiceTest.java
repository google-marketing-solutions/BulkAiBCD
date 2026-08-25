package com.bulkaibcd.service.output;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.GoogleSlidesClient;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.GenerateDeckRequest;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.FeatureConfigService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GenerateDeckServiceTest {

  private VideoMetadataRepository videoMetadataRepo;
  private AnalysisRequestRepository analysisRepo;
  private ObjectProvider<GoogleSlidesClient> slidesProvider;
  private GoogleSlidesClient slidesClient;
  private FeatureConfigService featureConfigService;
  private GenerateDeckService service;

  @BeforeEach
  void setUp() {
    videoMetadataRepo = mock(VideoMetadataRepository.class);
    analysisRepo = mock(AnalysisRequestRepository.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<GoogleSlidesClient> provider = mock(ObjectProvider.class);
    slidesProvider = provider;
    slidesClient = mock(GoogleSlidesClient.class);
    when(slidesProvider.getIfAvailable()).thenReturn(slidesClient);
    featureConfigService = mock(FeatureConfigService.class);

    service =
        new GenerateDeckService(
            videoMetadataRepo, analysisRepo, slidesProvider, featureConfigService);
  }

  @Test
  void executeValidationFailsWhenTokenMissing() {
    GenerateDeckRequest req = GenerateDeckRequest.builder().analysisId("ana-1").build();

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
    when(slidesProvider.getIfAvailable()).thenReturn(null);
    GenerateDeckRequest req =
        GenerateDeckRequest.builder().analysisId("ana-1").userAccessToken("tok").build();

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(resp.getBody()).containsEntry("error", "slides_service_unavailable");
            })
        .verifyComplete();
  }

  @Test
  void executeValidationFailsWhenVideoIdsEmpty() {
    GenerateDeckRequest req =
        GenerateDeckRequest.builder().analysisId("ana-1").userAccessToken("tok").videoIds(List.of()).build();

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(resp.getBody()).containsEntry("error", "videoIds_required");
            })
        .verifyComplete();
  }

  @Test
  void executeGeneratesDeckSuccessfully() throws Exception {
    GenerateDeckRequest req =
        GenerateDeckRequest.builder()
            .analysisId("ana-1")
            .userAccessToken("tok")
            .videoIds(List.of("v1"))
            .build();

    AnalysisRequestEntity analysis =
        AnalysisRequestEntity.builder()
            .analysisId("ana-1")
            .analysisType("standard")
            .brandName("Acme")
            .marketingObjective("awareness")
            .build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(analysis));

    VideoMetadataEntity v1 =
        VideoMetadataEntity.builder().id("ana-1_v1").analysisId("ana-1").videoId("v1").build();
    when(videoMetadataRepo.findById("ana-1_v1")).thenReturn(Mono.just(v1));

    when(featureConfigService.getFeaturesByTypeWithoutFormat("standard")).thenReturn(List.of());
    when(slidesClient.generateBulkPitchDeck(any())).thenReturn("https://docs.google.com/presentation/d/deck-123");

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              @SuppressWarnings("unchecked")
              List<Map<String, Object>> decks = (List<Map<String, Object>>) resp.getBody().get("decks");
              assertThat(decks).hasSize(1);
              assertThat(decks.get(0)).containsEntry("deckUrl", "https://docs.google.com/presentation/d/deck-123");
            })
        .verifyComplete();
  }

  @Test
  void executeMapsExceptionTo502() throws Exception {
    GenerateDeckRequest req =
        GenerateDeckRequest.builder()
            .analysisId("ana-1")
            .userAccessToken("tok")
            .videoIds(List.of("v1"))
            .build();

    when(analysisRepo.findById("ana-1")).thenReturn(Mono.error(new RuntimeException("Drive API error")));

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
              assertThat(resp.getBody()).containsEntry("error", "Drive API error");
            })
        .verifyComplete();
  }
}
