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
