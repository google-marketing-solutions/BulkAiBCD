package com.bulkaibcd.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ListAnalysesServiceTest {

  private AnalysisRequestRepository analysisRepo;
  private ListAnalysesService service;

  @BeforeEach
  void setUp() {
    analysisRepo = mock(AnalysisRequestRepository.class);
    service = new ListAnalysesService(analysisRepo, "default-user", false);
  }

  @Test
  void appendsLegacyAnalysesSoHistoricWorkDoesNotVanish() {
    ListAnalysesService withGrace =
        new ListAnalysesService(analysisRepo, "default-user", true);
    AnalysisRequestEntity own = AnalysisRequestEntity.builder().analysisId("1").requesterId("u1").build();
    AnalysisRequestEntity legacy =
        AnalysisRequestEntity.builder().analysisId("2").requesterId("default-user").build();
    when(analysisRepo.findByRequesterIdOrderByCreatedAtDesc("u1")).thenReturn(Flux.just(own));
    when(analysisRepo.findByRequesterIdOrderByCreatedAtDesc("default-user"))
        .thenReturn(Flux.just(legacy));

    StepVerifier.create(withGrace.execute("u1"))
        .expectNext(own)
        .expectNext(legacy)
        .verifyComplete();
  }

  @Test
  void executeReturnsListOfAnalysesForRequester() {
    AnalysisRequestEntity a1 = AnalysisRequestEntity.builder().analysisId("1").requesterId("u1").build();
    AnalysisRequestEntity a2 = AnalysisRequestEntity.builder().analysisId("2").requesterId("u1").build();

    when(analysisRepo.findByRequesterIdOrderByCreatedAtDesc("u1")).thenReturn(Flux.just(a1, a2));

    StepVerifier.create(service.execute("u1"))
        .expectNext(a1)
        .expectNext(a2)
        .verifyComplete();
  }
}
