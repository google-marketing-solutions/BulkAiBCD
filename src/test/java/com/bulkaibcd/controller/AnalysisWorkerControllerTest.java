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

import com.bulkaibcd.model.PollRequest;
import com.bulkaibcd.model.ProcessRequest;
import com.bulkaibcd.model.TaskRequest;
import com.bulkaibcd.service.analysis.PrepareAnalysisService;
import com.bulkaibcd.service.batch.CheckPhase1StatusService;
import com.bulkaibcd.service.batch.CheckPhase2StatusService;
// BEGIN-INTERNAL
import com.bulkaibcd.service.batch.CheckUploadStatusService;
// END-INTERNAL
import com.bulkaibcd.service.batch.ProcessPhase1ResultsService;
import com.bulkaibcd.service.batch.ProcessPhase2ResultsService;
import com.bulkaibcd.service.batch.StartPhase2Service;
import com.bulkaibcd.service.worker.ExtractRawMetadataService;
import com.bulkaibcd.service.worker.FetchScoringMetadataService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AnalysisWorkerControllerTest {

  private PrepareAnalysisService prepareAnalysisService;
  // BEGIN-INTERNAL
  private CheckUploadStatusService checkUploadStatusService;
  // END-INTERNAL
  private FetchScoringMetadataService fetchScoringMetadataService;
  private ExtractRawMetadataService extractRawMetadataService;
  private StartPhase2Service startPhase2Service;
  private CheckPhase2StatusService checkPhase2StatusService;
  private CheckPhase1StatusService checkPhase1StatusService;
  private ProcessPhase2ResultsService processPhase2ResultsService;
  private ProcessPhase1ResultsService processPhase1ResultsService;

  private AnalysisWorkerController controller;

  @BeforeEach
  void setUp() {
    prepareAnalysisService = mock(PrepareAnalysisService.class);
    fetchScoringMetadataService = mock(FetchScoringMetadataService.class);
    extractRawMetadataService = mock(ExtractRawMetadataService.class);
    startPhase2Service = mock(StartPhase2Service.class);
    checkPhase2StatusService = mock(CheckPhase2StatusService.class);
    checkPhase1StatusService = mock(CheckPhase1StatusService.class);
    processPhase2ResultsService = mock(ProcessPhase2ResultsService.class);
    processPhase1ResultsService = mock(ProcessPhase1ResultsService.class);

    controller =
        new AnalysisWorkerController(
            prepareAnalysisService,
            fetchScoringMetadataService,
            extractRawMetadataService,
            startPhase2Service,
            checkPhase2StatusService,
            checkPhase1StatusService,
            processPhase2ResultsService,
            processPhase1ResultsService);
    // BEGIN-INTERNAL
    checkUploadStatusService = mock(CheckUploadStatusService.class);
    ReflectionTestUtils.setField(controller, "checkUploadStatusService", checkUploadStatusService);
    // END-INTERNAL
  }

  @Test
  void prepareAnalysisDelegatesToService() {
    Map<String, String> payload = Map.of("analysisId", "ana-1");
    when(prepareAnalysisService.execute(payload)).thenReturn(Mono.just(ResponseEntity.ok("prepared")));

    StepVerifier.create(controller.prepareAnalysis(payload))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("prepared"))
        .verifyComplete();
    verify(prepareAnalysisService).execute(payload);
  }

  // BEGIN-INTERNAL
  @Test
  void checkUploadStatusDelegatesToService() {
    Map<String, Object> payload = Map.of("analysisId", "ana-1", "requestId", "req-1");
    when(checkUploadStatusService.execute(payload)).thenReturn(Mono.just(ResponseEntity.ok("upload-checked")));

    StepVerifier.create(controller.checkUploadStatus(payload))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("upload-checked"))
        .verifyComplete();
    verify(checkUploadStatusService).execute(payload);
  }
  // END-INTERNAL

  @Test
  void fetchMetadataSetsExecutionCountAndDelegates() {
    TaskRequest request = new TaskRequest();
    request.setAnalysisId("ana-1");
    when(fetchScoringMetadataService.execute(request)).thenReturn(Mono.just(ResponseEntity.ok("fetched")));

    StepVerifier.create(controller.fetchMetadata(request, 2))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("fetched"))
        .verifyComplete();
    assertThat(request.getExecutionCount()).isEqualTo(2);
    verify(fetchScoringMetadataService).execute(request);
  }

  @Test
  void extractRawMetadataSetsExecutionCountAndDelegates() {
    TaskRequest request = new TaskRequest();
    request.setAnalysisId("ana-1");
    when(extractRawMetadataService.execute(request)).thenReturn(Mono.just(ResponseEntity.ok("extracted")));

    StepVerifier.create(controller.extractRawMetadata(request, 1))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("extracted"))
        .verifyComplete();
    assertThat(request.getExecutionCount()).isEqualTo(1);
    verify(extractRawMetadataService).execute(request);
  }

  @Test
  void startPhase2DelegatesToService() {
    Map<String, String> payload = Map.of("analysisId", "ana-1");
    when(startPhase2Service.execute(payload)).thenReturn(Mono.just(ResponseEntity.ok("started")));

    StepVerifier.create(controller.startPhase2(payload))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("started"))
        .verifyComplete();
    verify(startPhase2Service).execute(payload);
  }

  @Test
  void checkPhase2StatusDelegatesToService() {
    PollRequest request = new PollRequest();
    when(checkPhase2StatusService.execute(request)).thenReturn(Mono.just(ResponseEntity.ok("polled2")));

    StepVerifier.create(controller.checkPhase2Status(request))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("polled2"))
        .verifyComplete();
    verify(checkPhase2StatusService).execute(request);
  }

  @Test
  void processPhase2ResultsDelegatesToService() {
    ProcessRequest request = new ProcessRequest();
    when(processPhase2ResultsService.execute(request)).thenReturn(Mono.just(ResponseEntity.ok("processed2")));

    StepVerifier.create(controller.processPhase2Results(request))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("processed2"))
        .verifyComplete();
    verify(processPhase2ResultsService).execute(request);
  }

  @Test
  void checkPhase1StatusDelegatesToService() {
    PollRequest request = new PollRequest();
    when(checkPhase1StatusService.execute(request)).thenReturn(Mono.just(ResponseEntity.ok("polled1")));

    StepVerifier.create(controller.checkPhase1Status(request))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("polled1"))
        .verifyComplete();
    verify(checkPhase1StatusService).execute(request);
  }

  @Test
  void processPhase1ResultsDelegatesToService() {
    ProcessRequest request = new ProcessRequest();
    when(processPhase1ResultsService.execute(request)).thenReturn(Mono.just(ResponseEntity.ok("processed1")));

    StepVerifier.create(controller.processPhase1Results(request))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("processed1"))
        .verifyComplete();
    verify(processPhase1ResultsService).execute(request);
  }
}
