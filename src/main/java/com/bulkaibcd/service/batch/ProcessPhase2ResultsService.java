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
 * Service responsible for handling Phase 2 Vertex AI Batch Prediction results processing webhooks.
 *
 * <p>Checks if the parent analysis is deleted, delegates final scoring parsing and Firestore
 * persistence to PipelineOrchestrator, and returns completion status to Cloud Tasks.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProcessPhase2ResultsService
    implements ApiService<ProcessRequest, ResponseEntity<String>> {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final BatchPredictionOrchestrator batchPredictionOrchestrator;

  @Override
  public Mono<ResponseEntity<String>> execute(ProcessRequest request) {
    log.info(
        "ProcessPhase2ResultsService: Received process-phase2-results request for analysisId: {},"
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
                    "ProcessPhase2ResultsService: Skipping results processing — parent status is"
                        + " DELETED");
                return Mono.just(ResponseEntity.ok("Skipped: parent DELETED"));
              }
              return runProcessPhase2Results(request);
            });
  }

  private Mono<ResponseEntity<String>> runProcessPhase2Results(ProcessRequest request) {
    return Mono.fromRunnable(
            () -> {
              try {
                batchPredictionOrchestrator.processPhase2Results(
                    request.getAnalysisId(), request.getGcsUri());
              } catch (Exception e) {
                log.error(
                    "ProcessPhase2ResultsService: Critical failure processing consolidated Phase 2"
                        + " batch results!",
                    e);
                throw new RuntimeException(e);
              }
            })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .then(
            Mono.just(
                ResponseEntity.ok("Phase 2 batch prediction results successfully processed")));
  }
}
