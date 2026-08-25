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

package com.bulkaibcd.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.SubmitAnalysisRequest;
import com.bulkaibcd.service.analysis.CancelAnalysisService;
import com.bulkaibcd.service.analysis.DeleteAnalysisService;
import com.bulkaibcd.service.analysis.GetAnalysisService;
import com.bulkaibcd.service.analysis.ListAnalysesService;
import com.bulkaibcd.service.analysis.SubmitAnalysisService;
import com.bulkaibcd.service.drive.DriveResolveService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class InputControllerTest {

  private DriveResolveService driveResolveService;
  private SubmitAnalysisService submitAnalysisService;
  private ListAnalysesService listAnalysesService;
  private GetAnalysisService getAnalysisService;
  private CancelAnalysisService cancelAnalysisService;
  private DeleteAnalysisService deleteAnalysisService;
  private InputController controller;

  @BeforeEach
  void setUp() {
    driveResolveService = mock(DriveResolveService.class);
    submitAnalysisService = mock(SubmitAnalysisService.class);
    listAnalysesService = mock(ListAnalysesService.class);
    getAnalysisService = mock(GetAnalysisService.class);
    cancelAnalysisService = mock(CancelAnalysisService.class);
    deleteAnalysisService = mock(DeleteAnalysisService.class);

    controller =
        new InputController(
            driveResolveService,
            submitAnalysisService,
            listAnalysesService,
            getAnalysisService,
            cancelAnalysisService,
            deleteAnalysisService);
  }

  @Test
  void submitAnalysisDelegatesToService() {
    SubmitAnalysisRequest req = SubmitAnalysisRequest.builder().analysisName("Test").build();
    when(submitAnalysisService.execute(req)).thenReturn(Mono.just(ResponseEntity.ok("ana-123")));

    StepVerifier.create(controller.submitAnalysis(req))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
              assertThat(resp.getBody()).isEqualTo("ana-123");
            })
        .verifyComplete();
    verify(submitAnalysisService).execute(req);
  }

  @Test
  void resolveDriveDelegatesToService() {
    Map<String, String> body = Map.of("url", "https://drive.google.com/folder");
    @SuppressWarnings("unchecked")
    ResponseEntity<Object> expectedResponse = (ResponseEntity<Object>) (ResponseEntity<?>) ResponseEntity.ok("resolved");
    when(driveResolveService.execute(body)).thenReturn(Mono.just(expectedResponse));

    StepVerifier.create(controller.resolveDrive(body))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("resolved"))
        .verifyComplete();
    verify(driveResolveService).execute(body);
  }

  @Test
  void listAnalysesDelegatesToService() {
    AnalysisRequestEntity a1 = AnalysisRequestEntity.builder().analysisId("1").build();
    when(listAnalysesService.execute("user-1")).thenReturn(Flux.just(a1));

    StepVerifier.create(controller.listAnalyses("user-1"))
        .expectNext(a1)
        .verifyComplete();
    verify(listAnalysesService).execute("user-1");
  }

  @Test
  void getAnalysisDelegatesToService() {
    AnalysisRequestEntity a1 = AnalysisRequestEntity.builder().analysisId("1").build();
    when(getAnalysisService.execute("1")).thenReturn(Mono.just(ResponseEntity.ok(a1)));

    StepVerifier.create(controller.getAnalysis("1"))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(a1))
        .verifyComplete();
    verify(getAnalysisService).execute("1");
  }

  @Test
  void cancelAnalysisDelegatesToService() {
    when(cancelAnalysisService.execute("1")).thenReturn(Mono.just(ResponseEntity.ok("Cancelled")));

    StepVerifier.create(controller.cancelAnalysis("1"))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("Cancelled"))
        .verifyComplete();
    verify(cancelAnalysisService).execute("1");
  }

  @Test
  void deleteAnalysisDelegatesToService() {
    when(deleteAnalysisService.execute("1")).thenReturn(Mono.just(ResponseEntity.ok("Deleted")));

    StepVerifier.create(controller.deleteAnalysis("1"))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("Deleted"))
        .verifyComplete();
    verify(deleteAnalysisService).execute("1");
  }
}

