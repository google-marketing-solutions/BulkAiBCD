package com.bulkaibcd.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.ProcessRequest;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ProcessPhase2ResultsServiceTest {

  private AnalysisRequestRepository analysisRepo;
  private BatchPredictionOrchestrator batchOrchestrator;
  private ProcessPhase2ResultsService service;

  @BeforeEach
  void setUp() {
    analysisRepo = mock(AnalysisRequestRepository.class);
    batchOrchestrator = mock(BatchPredictionOrchestrator.class);
    service = new ProcessPhase2ResultsService(analysisRepo, batchOrchestrator);
  }

  @Test
  void executeSkipsWhenParentDeleted() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("DELETED").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    ProcessRequest req = new ProcessRequest();
    req.setAnalysisId("ana-1");

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).contains("Skipped: parent DELETED");
            })
        .verifyComplete();
  }

  @Test
  void executeProcessesPhase2Results() throws Exception {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PROCESSING").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));
    doNothing().when(batchOrchestrator).processPhase2Results("ana-1", "gs://bucket/out2/");

    ProcessRequest req = new ProcessRequest();
    req.setAnalysisId("ana-1");
    req.setGcsUri("gs://bucket/out2/");

    StepVerifier.create(service.execute(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).contains("Phase 2 batch prediction results successfully processed");
            })
        .verifyComplete();

    verify(batchOrchestrator).processPhase2Results("ana-1", "gs://bucket/out2/");
  }
}
