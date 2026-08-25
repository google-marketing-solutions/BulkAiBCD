package com.bulkaibcd.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.UploadUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DeleteAnalysisServiceTest {

  private VideoInputRepository videoInputRepo;
  private VideoMetadataRepository videoMetadataRepo;
  private AnalysisRequestRepository analysisRepo;
  private ObjectProvider<UploadUrlService> uploadUrlServiceProvider;
  private UploadUrlService uploadUrlService;
  private DeleteAnalysisService service;

  @BeforeEach
  void setUp() {
    videoInputRepo = mock(VideoInputRepository.class);
    videoMetadataRepo = mock(VideoMetadataRepository.class);
    analysisRepo = mock(AnalysisRequestRepository.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<UploadUrlService> provider = mock(ObjectProvider.class);
    uploadUrlServiceProvider = provider;
    uploadUrlService = mock(UploadUrlService.class);
    when(uploadUrlServiceProvider.getIfAvailable()).thenReturn(uploadUrlService);

    service =
        new DeleteAnalysisService(
            videoInputRepo, videoMetadataRepo, analysisRepo, uploadUrlServiceProvider);
  }

  @Test
  void executeDeletesVideoInputsMetadataAndParent() {
    VideoInputEntity v1 =
        VideoInputEntity.builder()
            .id("ana-1_v1")
            .analysisId("ana-1")
            .gcsObjectId("bucket/video1.mp4")
            .build();
    when(videoInputRepo.findByAnalysisId("ana-1")).thenReturn(Flux.just(v1));
    when(videoInputRepo.deleteById("ana-1_v1")).thenReturn(Mono.empty());
    when(videoMetadataRepo.deleteById("ana-1_v1")).thenReturn(Mono.empty());
    when(analysisRepo.deleteById("ana-1")).thenReturn(Mono.empty());

    StepVerifier.create(service.execute("ana-1"))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo("DELETED");
            })
        .verifyComplete();

    verify(uploadUrlService).delete("bucket/video1.mp4");
    verify(videoInputRepo).deleteById("ana-1_v1");
    verify(videoMetadataRepo).deleteById("ana-1_v1");
    verify(analysisRepo).deleteById("ana-1");
  }
}
