package com.bulkaibcd.service.analysis;

import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.google.cloud.Timestamp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service responsible for cancelling an in-flight analysis run.
 *
 * <p>Marks the parent analysis and its active video metadata records as CANCELLED. Any
 * already-enqueued Cloud Tasks short-circuit upon detecting this status.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CancelAnalysisService implements ApiService<String, ResponseEntity<String>> {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoMetadataRepository videoMetadataRepository;

  /**
   * Executes the cancellation workflow for the specified analysis ID.
   *
   * @param analysisId The unique identifier of the analysis to cancel
   * @return A reactive {@link Mono} emitting the HTTP response indicating cancellation status
   */
  @Override
  public Mono<ResponseEntity<String>> execute(String analysisId) {
    log.info("CancelAnalysisService: Initiating cancellation for analysisId: {}", analysisId);
    Timestamp now = Timestamp.now();

    return analysisRequestRepository
        .findById(analysisId)
        .flatMap(
            parent -> {
              if (AnalysisStatus.COMPLETED.name().equals(parent.getAnalysisStatus())
                  || AnalysisStatus.CANCELLED.name().equals(parent.getAnalysisStatus())) {
                log.info(
                    "CancelAnalysisService: Analysis {} is already terminal ({})",
                    analysisId,
                    parent.getAnalysisStatus());
                return Mono.just(ResponseEntity.ok(parent.getAnalysisStatus()));
              }
              parent.setAnalysisStatus(AnalysisStatus.CANCELLED.name());
              parent.setUpdatedAt(now);
              return analysisRequestRepository
                  .save(parent)
                  .then(
                      videoMetadataRepository
                          .findByAnalysisId(analysisId)
                          .flatMap(
                              videoMetadata -> {
                                if (!AnalysisStatus.COMPLETED
                                    .name()
                                    .equals(videoMetadata.getStatus())) {
                                  videoMetadata.setStatus(AnalysisStatus.CANCELLED.name());
                                  if (videoMetadata.getErrorMessage() == null) {
                                    videoMetadata.setErrorMessage("cancelled by user");
                                  }
                                  return videoMetadataRepository.save(videoMetadata);
                                }
                                return Mono.just(videoMetadata);
                              })
                          .then())
                  .thenReturn(ResponseEntity.ok(AnalysisStatus.CANCELLED.name()));
            })
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }
}
