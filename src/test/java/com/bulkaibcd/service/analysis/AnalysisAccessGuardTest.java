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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AnalysisAccessGuardTest {

  private static final String ANALYSIS_ID = "ana-1";

  private AnalysisRequestRepository analysisRepo;

  @BeforeEach
  void setUp() {
    analysisRepo = mock(AnalysisRequestRepository.class);
  }

  @Test
  void ownerIsGrantedAccess() {
    AnalysisRequestEntity owned = analysisOwnedBy("jdoe");
    when(analysisRepo.findById(ANALYSIS_ID)).thenReturn(Mono.just(owned));

    StepVerifier.create(guard(true).requireAccess(ANALYSIS_ID, "jdoe"))
        .expectNext(owned)
        .verifyComplete();
  }

  @Test
  void otherUsersAnalysisIsIndistinguishableFromAMissingOne() {
    when(analysisRepo.findById(ANALYSIS_ID)).thenReturn(Mono.just(analysisOwnedBy("someone-else")));

    StepVerifier.create(guard(true).requireAccess(ANALYSIS_ID, "jdoe")).verifyComplete();
  }

  @Test
  void unattributedCallerIsDeniedWithoutTouchingTheRepository() {
    StepVerifier.create(guard(true).requireAccess(ANALYSIS_ID, null)).verifyComplete();
    StepVerifier.create(guard(true).requireAccess(ANALYSIS_ID, "  ")).verifyComplete();
  }

  @Test
  void legacyAnalysesStayReachableWhileTheGracePeriodIsOpen() {
    AnalysisRequestEntity legacy = analysisOwnedBy("default-user");
    when(analysisRepo.findById(ANALYSIS_ID)).thenReturn(Mono.just(legacy));

    StepVerifier.create(guard(true).requireAccess(ANALYSIS_ID, "jdoe"))
        .expectNext(legacy)
        .verifyComplete();
  }

  @Test
  void legacyAnalysesAreDeniedOnceTheGracePeriodCloses() {
    when(analysisRepo.findById(ANALYSIS_ID)).thenReturn(Mono.just(analysisOwnedBy("default-user")));

    StepVerifier.create(guard(false).requireAccess(ANALYSIS_ID, "jdoe")).verifyComplete();
  }

  @Test
  void missingAnalysisCompletesEmpty() {
    when(analysisRepo.findById(ANALYSIS_ID)).thenReturn(Mono.empty());

    StepVerifier.create(guard(true).requireAccess(ANALYSIS_ID, "jdoe")).verifyComplete();
  }

  private AnalysisAccessGuard guard(boolean allowLegacyAccess) {
    return new AnalysisAccessGuard(analysisRepo, "default-user", allowLegacyAccess);
  }

  private static AnalysisRequestEntity analysisOwnedBy(String requesterId) {
    return AnalysisRequestEntity.builder()
        .analysisId(ANALYSIS_ID)
        .requesterId(requesterId)
        .build();
  }
}
