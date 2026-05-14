package com.bulkaibcd.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.VideoMetadataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class OutputControllerTest {

  @Mock VideoMetadataRepository repo;

  @InjectMocks OutputController controller;

  @Test
  void getVideosProxiesRepositoryResult() {
    VideoMetadataEntity v1 = VideoMetadataEntity.builder().id("1").videoId("a").build();
    VideoMetadataEntity v2 = VideoMetadataEntity.builder().id("2").videoId("b").build();
    when(repo.findByAnalysisId(anyString())).thenReturn(Flux.just(v1, v2));

    StepVerifier.create(controller.getVideos("analysis-1"))
        .expectNext(v1)
        .expectNext(v2)
        .verifyComplete();
  }
}
