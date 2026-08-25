package com.bulkaibcd.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CancelAnalysisServiceTest {

  private AnalysisRequestRepository analysisRepo;
  private VideoMetadataRepository metadataRepo;
  private CancelAnalysisService service;

  @BeforeEach
  void setUp() {
    analysisRepo = mock(AnalysisRequestRepository.class);
    metadataRepo = mock(VideoMetadataRepository.class);
    service = new CancelAnalysisService(analysisRepo, metadataRepo);
  }

  @Test
  void cancelInFlightAnalysisMarksParentAndChildrenCancelled() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PROCESSING").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));
    when(analysisRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    VideoMetadataEntity v1 =
        VideoMetadataEntity.builder().id("ana-1_v1").analysisId("ana-1").status("PROCESSING").build();
    VideoMetadataEntity v2 =
        VideoMetadataEntity.builder().id("ana-1_v2").analysisId("ana-1").status("COMPLETED").build();
    when(metadataRepo.findByAnalysisId("ana-1")).thenReturn(Flux.just(v1, v2));
    when(metadataRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.execute("ana-1"))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo(AnalysisStatus.CANCELLED.name());
            })
        .verifyComplete();

    assertThat(parent.getAnalysisStatus()).isEqualTo(AnalysisStatus.CANCELLED.name());
    assertThat(v1.getStatus()).isEqualTo(AnalysisStatus.CANCELLED.name());
    assertThat(v1.getErrorMessage()).isEqualTo("cancelled by user");
    assertThat(v2.getStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void cancelAlreadyTerminalAnalysisReturnsCurrentStatusWithoutMutating() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("COMPLETED").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    StepVerifier.create(service.execute("ana-1"))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo("COMPLETED");
            })
        .verifyComplete();

    verify(analysisRepo, never()).save(any());
  }

  @Test
  void cancelNotFoundReturns404() {
    when(analysisRepo.findById("missing")).thenReturn(Mono.empty());

    StepVerifier.create(service.execute("missing"))
        .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
        .verifyComplete();
  }
}
