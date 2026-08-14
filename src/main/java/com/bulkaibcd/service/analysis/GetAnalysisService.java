/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
