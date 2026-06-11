package com.bulkaibcd.service.batch;

import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.ProcessRequest;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.service.analysis.ApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service responsible for handling Phase 1 Vertex AI Batch Prediction results processing webhooks.
 *
 * <p>Checks if the parent analysis is deleted, delegates GCS result parsing and Firestore
 * population to PipelineOrchestrator, and returns completion status to Cloud Tasks.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessPhase1ResultsService
    implements ApiService<ProcessRequest, ResponseEntity<String>> {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final BatchPredictionOrchestrator batchPredictionOrchestrator;

  @Override
  public Mono<ResponseEntity<String>> execute(ProcessRequest request) {
    log.info(
        "ProcessPhase1ResultsService: Received process-phase1-results request for analysisId: {},"
            + " output GCS: {}",
        request.getAnalysisId(),
        request.getGcsUri());
    return analysisRequestRepository
        .findById(request.getAnalysisId())
        .map(AnalysisRequestEntity::getAnalysisStatus)
        .defaultIfEmpty(AnalysisStatus.DELETED.name())
        .flatMap(
            status -> {
              if (AnalysisStatus.DELETED.name().equals(status)) {
                log.info(
                    "ProcessPhase1ResultsService: Skipping Phase 1 results processing — parent"
                        + " status is DELETED");
                return Mono.just(ResponseEntity.ok("Skipped: parent DELETED"));
              }
              return runProcessPhase1Results(request);
            });
  }

  private Mono<ResponseEntity<String>> runProcessPhase1Results(ProcessRequest request) {
    return Mono.fromRunnable(
            () -> {
              try {
                batchPredictionOrchestrator.processPhase1Results(
                    request.getAnalysisId(), request.getGcsUri());
              } catch (Exception e) {
                log.error(
                    "ProcessPhase1ResultsService: Critical failure processing consolidated Phase 1"
                        + " batch results!",
                    e);
                throw new RuntimeException(e);
              }
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .then(
            Mono.just(
                ResponseEntity.ok("Phase 1 batch prediction results successfully processed")));
  }
}
