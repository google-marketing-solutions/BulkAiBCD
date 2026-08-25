package com.bulkaibcd.service.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.GeminiClient;
import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.enums.AnalysisType;
import com.bulkaibcd.enums.ScoringDimension;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.TaskRequest;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.UploadUrlService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FetchScoringMetadataServiceTest {

  private AnalysisRequestRepository analysisRepo;
  private VideoMetadataRepository videoMetadataRepo;
  private VideoInputRepository videoInputRepo;
  private GeminiClient geminiClient;
  private ObjectProvider<UploadUrlService> uploadUrlServiceProvider;
  private UploadUrlService uploadUrlService;
  private FetchScoringMetadataService service;

  @BeforeEach
  void setUp() {
    analysisRepo = mock(AnalysisRequestRepository.class);
    videoMetadataRepo = mock(VideoMetadataRepository.class);
    videoInputRepo = mock(VideoInputRepository.class);
    geminiClient = mock(GeminiClient.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<UploadUrlService> provider = mock(ObjectProvider.class);
    uploadUrlServiceProvider = provider;
    uploadUrlService = mock(UploadUrlService.class);
    when(uploadUrlServiceProvider.getIfAvailable()).thenReturn(uploadUrlService);

    service =
        new FetchScoringMetadataService(
            analysisRepo,
            videoMetadataRepo,
            videoInputRepo,
            geminiClient,
            uploadUrlServiceProvider);
  }

  @Test
  void unknownPromptTypeReturns400() {
    TaskRequest req = new TaskRequest();
    req.setAnalysisId("ana-1");
    req.setPromptType("INVALID_SCORE");

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(resp.getBody()).isEqualTo("Unknown prompt type");
            })
        .verifyComplete();
  }

  @Test
  void parentCancelledSkipsTask() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("CANCELLED").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    TaskRequest req = new TaskRequest();
    req.setAnalysisId("ana-1");
    req.setPromptType(ScoringDimension.A_ATTRACT.name());

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).contains("Skipped: parent CANCELLED");
            })
        .verifyComplete();
  }

  @Test
  void alreadySetScoreSkipsGeminiCall() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PROCESSING").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    VideoMetadataEntity metadata =
        VideoMetadataEntity.builder().id("ana-1_v1").aScore(85).build();
    when(videoMetadataRepo.findById("ana-1_v1")).thenReturn(Mono.just(metadata));

    TaskRequest req = new TaskRequest();
    req.setAnalysisId("ana-1");
    req.setVideoId("v1");
    req.setPromptType(ScoringDimension.A_ATTRACT.name());

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo("Already processed");
            })
        .verifyComplete();
  }

  @Test
  void geminiSuccessUpdatesScoreAndChecksCompletion() throws Exception {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder()
            .analysisId("ana-1")
            .analysisType(AnalysisType.STANDARD.name())
            .analysisStatus("PROCESSING")
            .build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    VideoMetadataEntity metadata =
        VideoMetadataEntity.builder()
            .id("ana-1_v1")
            .analysisId("ana-1")
            .videoId("v1")
            .bScore(80)
            .cScore(75)
            .dScore(90)
            .assetName("Cool Spot")
            .build();
    when(videoMetadataRepo.findById("ana-1_v1")).thenReturn(Mono.just(metadata));
    when(videoMetadataRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    when(geminiClient.callGemini(any(), any(), any())).thenReturn("85");

    VideoInputEntity v1 =
        VideoInputEntity.builder()
            .id("ana-1_v1")
            .analysisId("ana-1")
            .videoId("v1")
            .sourceType("FILE")
            .gcsObjectId("bucket/vid.mp4")
            .build();
    when(videoInputRepo.findByAnalysisId("ana-1")).thenReturn(Flux.just(v1));
    when(videoInputRepo.findById("ana-1_v1")).thenReturn(Mono.just(v1));
    when(analysisRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    TaskRequest req = new TaskRequest();
    req.setAnalysisId("ana-1");
    req.setVideoId("v1");
    req.setPromptType(ScoringDimension.A_ATTRACT.name());

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo("Metadata updated");
            })
        .verifyComplete();

    assertThat(metadata.getAScore()).isEqualTo(85);
    assertThat(metadata.getStatus()).isEqualTo(AnalysisStatus.COMPLETED.name());
    verify(uploadUrlService).delete("bucket/vid.mp4");
  }
}
