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

import com.bulkaibcd.model.PollRequest;
import com.bulkaibcd.model.ProcessRequest;
import com.bulkaibcd.model.TaskRequest;
import com.bulkaibcd.service.analysis.PrepareAnalysisService;
import com.bulkaibcd.service.batch.CheckPhase1StatusService;
import com.bulkaibcd.service.batch.CheckPhase2StatusService;
import com.bulkaibcd.service.batch.CheckUploadStatusService;
import com.bulkaibcd.service.batch.ProcessPhase1ResultsService;
import com.bulkaibcd.service.batch.ProcessPhase2ResultsService;
import com.bulkaibcd.service.batch.StartPhase2Service;
import com.bulkaibcd.service.worker.ExtractRawMetadataService;
import com.bulkaibcd.service.worker.FetchScoringMetadataService;
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

/**
 * A controller handling worker endpoints invoked asynchronously by Cloud Tasks during video analysis pipelines.
 */
@RestController
@RequestMapping("/api/v2/worker")
@RequiredArgsConstructor
@Slf4j
public class AnalysisWorkerController {

  private final PrepareAnalysisService prepareAnalysisService;
  private final CheckUploadStatusService checkUploadStatusService;
  private final FetchScoringMetadataService fetchScoringMetadataService;
  private final ExtractRawMetadataService extractRawMetadataService;
  private final StartPhase2Service startPhase2Service;
  private final CheckPhase2StatusService checkPhase2StatusService;
  private final CheckPhase1StatusService checkPhase1StatusService;
  private final ProcessPhase2ResultsService processPhase2ResultsService;
  private final ProcessPhase1ResultsService processPhase1ResultsService;

  /**
   * Prepares the analysis job by ingesting Drive videos or initiating unlisted YouTube uploads.
   *
   * @param payload map containing the analysisId
   * @return a reactive {@link Mono} indicating preparation status
   */
  @PostMapping("/prepare")
  public Mono<ResponseEntity<String>> prepareAnalysis(@RequestBody Map<String, String> payload) {
    return prepareAnalysisService.execute(payload);
  }

  /**
   * Polls the status of an ongoing Boq unlisted video batch upload job.
   *
   * @param payload map containing analysisId, requestId, and attemptCount
   * @return a reactive {@link Mono} indicating upload poll status
   */
  @PostMapping("/check-upload-status")
  public Mono<ResponseEntity<String>> checkUploadStatus(@RequestBody Map<String, Object> payload) {
    return checkUploadStatusService.execute(payload);
  }

  /**
   * Fetches scoring metadata for an individual video analysis task.
   *
   * @param request the task execution request
   * @param executionCount retry attempt count from Cloud Tasks header
   * @return a reactive {@link Mono} with task completion status
   */
  @PostMapping("/fetch-metadata")
  public Mono<ResponseEntity<String>> fetchMetadata(
      @RequestBody TaskRequest request,
      @RequestHeader(value = "X-CloudTasks-TaskExecutionCount", defaultValue = "0")
          int executionCount) {
    request.setExecutionCount(executionCount);
    return fetchScoringMetadataService.execute(request);
  }

  /**
   * Extracts raw video metadata features for an individual video.
   *
   * @param request the task execution request
   * @param executionCount retry attempt count from Cloud Tasks header
   * @return a reactive {@link Mono} with raw metadata extraction status
   */
  @PostMapping("/extract-raw-metadata")
  public Mono<ResponseEntity<String>> extractRawMetadata(
      @RequestBody TaskRequest request,
      @RequestHeader(value = "X-CloudTasks-TaskExecutionCount", defaultValue = "0")
          int executionCount) {
    request.setExecutionCount(executionCount);
    return extractRawMetadataService.execute(request);
  }

  /**
   * Launches the Phase 2 analysis batch job for scored dimensions.
   *
   * @param payload map containing the analysisId
   * @return a reactive {@link Mono} with Phase 2 start status
   */
  @PostMapping("/start-phase2")
  public Mono<ResponseEntity<String>> startPhase2(@RequestBody Map<String, String> payload) {
    return startPhase2Service.execute(payload);
  }

  /**
   * Polls the status of the Phase 2 Vertex Batch job.
   *
   * @param request poll request containing batch job ID
   * @return a reactive {@link Mono} with polling response
   */
  @PostMapping("/check-phase2-status")
  public Mono<ResponseEntity<String>> checkPhase2Status(@RequestBody PollRequest request) {
    return checkPhase2StatusService.execute(request);
  }

  /**
   * Processes the output JSONL predictions from Phase 2.
   *
   * @param request processing request containing GCS output URI
   * @return a reactive {@link Mono} with results processing status
   */
  @PostMapping("/process-phase2-results")
  public Mono<ResponseEntity<String>> processPhase2Results(@RequestBody ProcessRequest request) {
    return processPhase2ResultsService.execute(request);
  }

  /**
   * Polls the status of the Phase 1 Vertex Batch prediction job.
   *
   * @param request poll request containing batch job ID
   * @return a reactive {@link Mono} with polling response
   */
  @PostMapping("/check-phase1-status")
  public Mono<ResponseEntity<String>> checkPhase1Status(@RequestBody PollRequest request) {
    return checkPhase1StatusService.execute(request);
  }

  /**
   * Processes the output JSONL predictions from Phase 1.
   *
   * @param request processing request containing GCS output URI
   * @return a reactive {@link Mono} with results processing status
   */
  @PostMapping("/process-phase1-results")
  public Mono<ResponseEntity<String>> processPhase1Results(@RequestBody ProcessRequest request) {
    return processPhase1ResultsService.execute(request);
  }
}
