package com.bulkaibcd.controller;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.SubmitAnalysisRequest;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.service.analysis.CancelAnalysisService;
import com.bulkaibcd.service.drive.DriveResolveService;
import com.bulkaibcd.service.analysis.DeleteAnalysisService;
import com.bulkaibcd.service.analysis.GetAnalysisService;
import com.bulkaibcd.service.analysis.ListAnalysesService;
import com.bulkaibcd.service.analysis.SubmitAnalysisService;
import com.google.cloud.Timestamp;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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

@RestController
@RequestMapping("/api/v2/input")
@RequiredArgsConstructor
@Slf4j
public class InputController {

  private final DriveResolveService driveResolveService;
  private final SubmitAnalysisService submitAnalysisService;
  private final ListAnalysesService listAnalysesService;
  private final GetAnalysisService getAnalysisService;
  private final CancelAnalysisService cancelAnalysisService;
  private final DeleteAnalysisService deleteAnalysisService;

  @PostMapping("/submit")
  public Mono<ResponseEntity<String>> submitAnalysis(@RequestBody SubmitAnalysisRequest request) {
    return submitAnalysisService.execute(request);
  }

  /**
   * Expands a Drive URL (file or folder) into a list of video entries the UI
   * can add to the queue. Returns 502 with the Drive API's error message when
   * the file isn't shared with the runtime SA, so the UI can snackbar it
   * instead of silently enqueuing a broken row.
   */
  @PostMapping("/drive-resolve")
  public Mono<ResponseEntity<?>> resolveDrive(@RequestBody Map<String, String> body) {
    return driveResolveService.execute(body);
  }

  @GetMapping("/list/{requesterId}")
  public Flux<AnalysisRequestEntity> listAnalyses(@PathVariable String requesterId) {
    return listAnalysesService.execute(requesterId);
  }

  @GetMapping("/{analysisId}")
  public Mono<ResponseEntity<AnalysisRequestEntity>> getAnalysis(@PathVariable String analysisId) {
    return getAnalysisService.execute(analysisId);
  }

  @PostMapping("/{analysisId}/cancel")
  public Mono<ResponseEntity<String>> cancelAnalysis(@PathVariable String analysisId) {
    return cancelAnalysisService.execute(analysisId);
  }

  @DeleteMapping("/{analysisId}")
  public Mono<ResponseEntity<String>> deleteAnalysis(@PathVariable String analysisId) {
    return deleteAnalysisService.execute(analysisId);
  }
}
