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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/** Service responsible for retrieving the list of analysis runs for a specific requester. */
@Service
@Slf4j
public class ListAnalysesService implements FluxApiService<String, AnalysisRequestEntity> {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final String legacyRequesterId;
  private final boolean allowLegacyAccess;

  public ListAnalysesService(
      AnalysisRequestRepository analysisRequestRepository,
      @Value("${app.identity.legacy-requester-id:default-user}") String legacyRequesterId,
      @Value("${app.identity.allow-legacy-access:true}") boolean allowLegacyAccess) {
    this.analysisRequestRepository = analysisRequestRepository;
    this.legacyRequesterId = legacyRequesterId;
    this.allowLegacyAccess = allowLegacyAccess;
  }

  /**
   * Retrieves the analysis requests belonging to the given requester, newest first.
   *
   * <p>Jobs created before requester identity was captured carry a placeholder requester and would
   * otherwise vanish from every list the moment real LDAPs start being recorded. While {@code
   * app.identity.allow-legacy-access} is set they are appended to the caller's own jobs, which
   * keeps historic work reachable until it has been migrated or aged out.
   *
   * @param requesterId The attribution id of the user requesting the list
   * @return A reactive {@link Flux} emitting the matching {@link AnalysisRequestEntity} records
   */
  @Override
  public Flux<AnalysisRequestEntity> execute(String requesterId) {
    log.info("ListAnalysesService: Fetching analysis list for requesterId: {}", requesterId);
    Flux<AnalysisRequestEntity> owned =
        analysisRequestRepository.findByRequesterIdOrderByCreatedAtDesc(requesterId);
    if (!allowLegacyAccess || legacyRequesterId.equals(requesterId)) {
      return owned;
    }
    return owned.concatWith(
        analysisRequestRepository.findByRequesterIdOrderByCreatedAtDesc(legacyRequesterId));
  }
}
