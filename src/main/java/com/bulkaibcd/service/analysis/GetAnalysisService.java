package com.bulkaibcd.service.analysis;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Service responsible for retrieving a singular analysis request by its ID. */
@Service
@Slf4j
@RequiredArgsConstructor
public class GetAnalysisService
    implements ApiService<String, ResponseEntity<AnalysisRequestEntity>> {

  private final AnalysisRequestRepository analysisRequestRepository;

  /**
   * Retrieves the analysis request entity for the specified analysis ID.
   *
   * @param analysisId The unique identifier of the analysis request
   * @return A reactive {@link Mono} emitting the HTTP response containing the entity, or 404 if not
   *     found
   */
  @Override
  public Mono<ResponseEntity<AnalysisRequestEntity>> execute(String analysisId) {
    log.info("GetAnalysisService: Fetching analysis for analysisId: {}", analysisId);
    return analysisRequestRepository
        .findById(analysisId)
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }
}
