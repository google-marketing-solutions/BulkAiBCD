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

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.SubmitAnalysisRequest;
import com.bulkaibcd.model.YouTubeVideoInfoDto;
import com.bulkaibcd.service.analysis.CancelAnalysisService;
import com.bulkaibcd.service.analysis.DeleteAnalysisService;
import com.bulkaibcd.service.analysis.GetAnalysisService;
import com.bulkaibcd.service.analysis.ListAnalysesService;
import com.bulkaibcd.service.analysis.SubmitAnalysisService;
import com.bulkaibcd.service.drive.DriveResolveService;
import com.bulkaibcd.service.youtube.YouTubeResolveService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A controller handling video analysis input submission, URL resolution, and lifecycle endpoints.
 */
@RestController
@RequestMapping("/api/v2/input")
@RequiredArgsConstructor
@Slf4j
public class InputController {

  private final DriveResolveService driveResolveService;
  private final YouTubeResolveService youTubeResolveService;
  private final SubmitAnalysisService submitAnalysisService;
  private final ListAnalysesService listAnalysesService;
  private final GetAnalysisService getAnalysisService;
  private final CancelAnalysisService cancelAnalysisService;
  private final DeleteAnalysisService deleteAnalysisService;

  /**
   * Submits a new batch video analysis job for processing.
   *
   * @param request the submission payload containing metadata and video inputs
   * @return a reactive {@link Mono} with the generated analysis ID
   */
  @PostMapping("/submit")
  public Mono<ResponseEntity<String>> submitAnalysis(@RequestBody SubmitAnalysisRequest request) {
    return submitAnalysisService.execute(request);
  }

  /**
   * Expands a Google Drive URL (file or folder) into individual video entries.
   *
   * @param body map containing the drive URL
   * @return a reactive {@link Mono} containing the resolved file items
   */
  @PostMapping("/drive-resolve")
  public Mono<ResponseEntity<?>> resolveDrive(@RequestBody Map<String, String> body) {
    return driveResolveService.execute(body);
  }

  /**
   * Resolves a list of YouTube URLs or video IDs, extracting titles and detecting unlisted status.
   *
   * @param body map containing the list of URLs under the "urls" key
   * @return a reactive {@link Flux} emitting resolved video info records
   */
  @PostMapping("/youtube-resolve")
  public Flux<YouTubeVideoInfoDto> resolveYouTube(@RequestBody Map<String, List<String>> body) {
    List<String> urls = body != null ? body.get("urls") : List.of();
    return youTubeResolveService.resolveVideos(urls);
  }

  /**
   * Lists all analysis jobs requested by a given user.
   *
   * @param requesterId the user identifier
   * @return a reactive {@link Flux} of analysis request entities
   */
  @GetMapping("/list/{requesterId}")
  public Flux<AnalysisRequestEntity> listAnalyses(@PathVariable String requesterId) {
    return listAnalysesService.execute(requesterId);
  }

  /**
   * Retrieves the status and details of a single analysis job.
   *
   * @param analysisId the unique analysis identifier
   * @return a reactive {@link Mono} with the analysis entity
   */
  @GetMapping("/{analysisId}")
  public Mono<ResponseEntity<AnalysisRequestEntity>> getAnalysis(@PathVariable String analysisId) {
    return getAnalysisService.execute(analysisId);
  }

  /**
   * Cancels an ongoing analysis job.
   *
   * @param analysisId the unique analysis identifier
   * @return a reactive {@link Mono} indicating cancellation status
   */
  @PostMapping("/{analysisId}/cancel")
  public Mono<ResponseEntity<String>> cancelAnalysis(@PathVariable String analysisId) {
    return cancelAnalysisService.execute(analysisId);
  }

  /**
   * Deletes an analysis record and its child records.
   *
   * @param analysisId the unique analysis identifier
   * @return a reactive {@link Mono} indicating deletion status
   */
  @DeleteMapping("/{analysisId}")
  public Mono<ResponseEntity<String>> deleteAnalysis(@PathVariable String analysisId) {
    return deleteAnalysisService.execute(analysisId);
  }
}
