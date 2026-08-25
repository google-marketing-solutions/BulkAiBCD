package com.bulkaibcd.service.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GetAnalysisServiceTest {

  private AnalysisRequestRepository analysisRepo;
  private GetAnalysisService service;

  @BeforeEach
  void setUp() {
    analysisRepo = mock(AnalysisRequestRepository.class);
    service = new GetAnalysisService(analysisRepo);
  }

  @Test
  void executeReturnsFoundEntity() {
    AnalysisRequestEntity entity =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisName("Test").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(entity));

    StepVerifier.create(service.execute("ana-1"))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).isEqualTo(entity);
            })
        .verifyComplete();
  }

  @Test
  void executeReturnsNotFoundWhenMissing() {
    when(analysisRepo.findById("missing")).thenReturn(Mono.empty());

    StepVerifier.create(service.execute("missing"))
        .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
        .verifyComplete();
  }
}
