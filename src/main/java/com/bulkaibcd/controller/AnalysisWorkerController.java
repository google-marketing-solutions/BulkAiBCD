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

import com.bulkaibcd.service.analysis.PrepareAnalysisService;
import com.bulkaibcd.service.batch.CheckPhase1StatusService;
import com.bulkaibcd.service.batch.CheckPhase2StatusService;
import com.bulkaibcd.service.batch.ProcessPhase1ResultsService;
import com.bulkaibcd.service.batch.ProcessPhase2ResultsService;
import com.bulkaibcd.service.batch.StartPhase2Service;
import com.bulkaibcd.service.worker.ExtractRawMetadataService;
import com.bulkaibcd.service.worker.FetchScoringMetadataService;
import com.bulkaibcd.model.PollRequest;
import com.bulkaibcd.model.ProcessRequest;
import com.bulkaibcd.model.TaskRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v2/worker")
@RequiredArgsConstructor
@Slf4j
public class AnalysisWorkerController {

  private final PrepareAnalysisService prepareAnalysisService;
  private final FetchScoringMetadataService fetchScoringMetadataService;
  private final ExtractRawMetadataService extractRawMetadataService;
  private final StartPhase2Service startPhase2Service;
  private final CheckPhase2StatusService checkPhase2StatusService;
  private final CheckPhase1StatusService checkPhase1StatusService;
  private final ProcessPhase2ResultsService processPhase2ResultsService;
  private final ProcessPhase1ResultsService processPhase1ResultsService;

  @PostMapping("/prepare")
  public Mono<ResponseEntity<String>> prepareAnalysis(@RequestBody Map<String, String> payload) {
    return prepareAnalysisService.execute(payload);
  }

  @PostMapping("/fetch-metadata")
  public Mono<ResponseEntity<String>> fetchMetadata(
      @RequestBody TaskRequest request,
      @RequestHeader(value = "X-CloudTasks-TaskExecutionCount", defaultValue = "0")
          int executionCount) {
    request.setExecutionCount(executionCount);
    return fetchScoringMetadataService.execute(request);
  }

  @PostMapping("/extract-raw-metadata")
  public Mono<ResponseEntity<String>> extractRawMetadata(
      @RequestBody TaskRequest request,
      @RequestHeader(value = "X-CloudTasks-TaskExecutionCount", defaultValue = "0")
          int executionCount) {
    request.setExecutionCount(executionCount);
    return extractRawMetadataService.execute(request);
  }

  @PostMapping("/start-phase2")
  public Mono<ResponseEntity<String>> startPhase2(@RequestBody Map<String, String> payload) {
    return startPhase2Service.execute(payload);
  }

  @PostMapping("/check-phase2-status")
  public Mono<ResponseEntity<String>> checkPhase2Status(@RequestBody PollRequest request) {
    return checkPhase2StatusService.execute(request);
  }

  @PostMapping("/process-phase2-results")
  public Mono<ResponseEntity<String>> processPhase2Results(@RequestBody ProcessRequest request) {
    return processPhase2ResultsService.execute(request);
  }

  @PostMapping("/check-phase1-status")
  public Mono<ResponseEntity<String>> checkPhase1Status(@RequestBody PollRequest request) {
    return checkPhase1StatusService.execute(request);
  }

  @PostMapping("/process-phase1-results")
  public Mono<ResponseEntity<String>> processPhase1Results(@RequestBody ProcessRequest request) {
    return processPhase1ResultsService.execute(request);
  }
}
