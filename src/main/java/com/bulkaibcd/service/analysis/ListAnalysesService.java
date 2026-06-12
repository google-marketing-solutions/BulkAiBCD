package com.bulkaibcd.service.analysis;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** Service responsible for retrieving the list of analysis runs for a specific requester. */
@Service
@Slf4j
@RequiredArgsConstructor
public class ListAnalysesService implements FluxApiService<String, AnalysisRequestEntity> {

  private final AnalysisRequestRepository analysisRequestRepository;

  /**
   * Retrieves all analysis requests associated with the given requester ID, ordered by creation
   * date descending.
   *
   * @param requesterId The ID of the user requesting the list
   * @return A reactive {@link Flux} emitting the matching {@link AnalysisRequestEntity} records
   */
  @Override
  public Flux<AnalysisRequestEntity> execute(String requesterId) {
    log.info("ListAnalysesService: Fetching analysis list for requesterId: {}", requesterId);
    return analysisRequestRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId);
  }
}
