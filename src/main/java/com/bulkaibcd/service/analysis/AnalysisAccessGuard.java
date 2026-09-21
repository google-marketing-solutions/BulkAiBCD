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
import reactor.core.publisher.Mono;

/**
 * Gate that confirms a caller is entitled to act on a particular analysis.
 *
 * <p>A denied request completes empty rather than raising a distinct error, so callers surface the
 * same 404 for "this analysis does not exist" and "this analysis is not yours". Distinguishing the
 * two would let any signed-in user probe for the existence of other people's jobs.
 */
@Service
@Slf4j
public class AnalysisAccessGuard {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final String legacyRequesterId;
  private final boolean allowLegacyAccess;

  public AnalysisAccessGuard(
      AnalysisRequestRepository analysisRequestRepository,
      @Value("${app.identity.legacy-requester-id:default-user}") String legacyRequesterId,
      @Value("${app.identity.allow-legacy-access:true}") boolean allowLegacyAccess) {
    this.analysisRequestRepository = analysisRequestRepository;
    this.legacyRequesterId = legacyRequesterId;
    this.allowLegacyAccess = allowLegacyAccess;
  }

  /**
   * Emits the analysis when the caller may act on it, and completes empty otherwise.
   *
   * @param analysisId the analysis being acted upon
   * @param requesterId the attribution id of the caller, or null when unauthenticated
   * @return a {@link Mono} emitting the analysis if access is granted, empty if it is not
   */
  public Mono<AnalysisRequestEntity> requireAccess(String analysisId, String requesterId) {
    if (requesterId == null || requesterId.isBlank()) {
      log.warn("AnalysisAccessGuard: denying unattributed access to analysisId: {}", analysisId);
      return Mono.empty();
    }
    return analysisRequestRepository
        .findById(analysisId)
        .filter(analysis -> isAccessible(analysis, requesterId));
  }

  private boolean isAccessible(AnalysisRequestEntity analysis, String requesterId) {
    String owner = analysis.getRequesterId();
    if (requesterId.equals(owner)) {
      return true;
    }
    if (allowLegacyAccess && legacyRequesterId.equals(owner)) {
      return true;
    }
    log.warn(
        "AnalysisAccessGuard: {} denied access to analysisId: {} owned by another requester",
        requesterId,
        analysis.getAnalysisId());
    return false;
  }
}
