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
import com.bulkaibcd.service.analysis.AnalysisAccessGuard;
import com.bulkaibcd.service.analysis.CancelAnalysisService;
import com.bulkaibcd.service.analysis.DeleteAnalysisService;
import com.bulkaibcd.service.analysis.GetAnalysisService;
import com.bulkaibcd.service.analysis.ListAnalysesService;
import com.bulkaibcd.service.analysis.SubmitAnalysisService;
import com.bulkaibcd.service.drive.DriveResolveService;
import com.bulkaibcd.service.youtube.YouTubeResolveService;
import com.bulkaibcd.web.RequesterContext;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
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
 *
 * <p>Every endpoint that touches a stored analysis derives the caller from the verified IAP
 * assertion rather than from the request. Client-supplied identity is ignored outright: the browser
 * bundle is not a trustworthy source for the field that both scopes the data and attributes the
 * downstream Boq call.
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
  private final AnalysisAccessGuard analysisAccessGuard;
  private final RequesterContext requesterContext;

  /**
   * Submits a new batch video analysis job for processing.
   *
   * <p>The requester recorded against the job is taken from the IAP assertion, overwriting whatever
   * the client sent.
   *
   * @param request the submission payload containing metadata and video inputs
   * @return a reactive {@link Mono} with the generated analysis ID, or 401 when unattributable
   */
  @PostMapping("/submit")
  public Mono<ResponseEntity<String>> submitAnalysis(@RequestBody SubmitAnalysisRequest request) {
    String requesterId = requesterContext.current().attributionId();
    if (requesterId == null) {
      log.warn("InputController: rejecting unattributed analysis submission");
      return Mono.just(
          ResponseEntity.status(HttpStatus.UNAUTHORIZED)
              .body("Caller identity could not be verified"));
    }
    request.setRequesterId(requesterId);
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
   * Lists the analysis jobs belonging to the calling user.
   *
   * <p>The requester is no longer accepted as a path variable; doing so let any signed-in user read
   * another user's job list simply by editing the URL.
   *
   * @return a reactive {@link Flux} of analysis request entities owned by the caller
   */
  @GetMapping("/list")
  public Flux<AnalysisRequestEntity> listAnalyses() {
    String requesterId = requesterContext.current().attributionId();
    if (requesterId == null) {
      log.warn("InputController: rejecting unattributed analysis list request");
      return Flux.empty();
    }
    return listAnalysesService.execute(requesterId);
  }

  /**
   * Retrieves the status and details of a single analysis job owned by the caller.
   *
   * @param analysisId the unique analysis identifier
   * @return a reactive {@link Mono} with the analysis entity, or 404 when absent or not the
   *     caller's
   */
  @GetMapping("/{analysisId}")
  public Mono<ResponseEntity<AnalysisRequestEntity>> getAnalysis(@PathVariable String analysisId) {
    return guard(analysisId)
        .flatMap(allowed -> getAnalysisService.execute(analysisId))
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  /**
   * Cancels an ongoing analysis job owned by the caller.
   *
   * @param analysisId the unique analysis identifier
   * @return a reactive {@link Mono} indicating cancellation status
   */
  @PostMapping("/{analysisId}/cancel")
  public Mono<ResponseEntity<String>> cancelAnalysis(@PathVariable String analysisId) {
    return guard(analysisId)
        .flatMap(allowed -> cancelAnalysisService.execute(analysisId))
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  /**
   * Deletes an analysis record and its child records, provided the caller owns it.
   *
   * @param analysisId the unique analysis identifier
   * @return a reactive {@link Mono} indicating deletion status
   */
  @DeleteMapping("/{analysisId}")
  public Mono<ResponseEntity<String>> deleteAnalysis(@PathVariable String analysisId) {
    return guard(analysisId)
        .flatMap(allowed -> deleteAnalysisService.execute(analysisId))
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  /** Emits once when the caller may act on the analysis, and completes empty otherwise. */
  private Mono<AnalysisRequestEntity> guard(String analysisId) {
    return analysisAccessGuard.requireAccess(analysisId, requesterContext.current().attributionId());
  }
}
