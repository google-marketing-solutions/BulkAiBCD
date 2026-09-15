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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.bulkaibcd.web.Requester;
import com.bulkaibcd.web.RequesterContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class InputControllerTest {

  private static final Requester GOOGLER =
      new Requester(Requester.Kind.GOOGLER, "jdoe", "jdoe@google.com", "sub-1");

  private DriveResolveService driveResolveService;
  private YouTubeResolveService youTubeResolveService;
  private SubmitAnalysisService submitAnalysisService;
  private ListAnalysesService listAnalysesService;
  private GetAnalysisService getAnalysisService;
  private CancelAnalysisService cancelAnalysisService;
  private DeleteAnalysisService deleteAnalysisService;
  private AnalysisAccessGuard analysisAccessGuard;
  private RequesterContext requesterContext;
  private InputController controller;

  @BeforeEach
  void setUp() {
    driveResolveService = mock(DriveResolveService.class);
    youTubeResolveService = mock(YouTubeResolveService.class);
    submitAnalysisService = mock(SubmitAnalysisService.class);
    listAnalysesService = mock(ListAnalysesService.class);
    getAnalysisService = mock(GetAnalysisService.class);
    cancelAnalysisService = mock(CancelAnalysisService.class);
    deleteAnalysisService = mock(DeleteAnalysisService.class);
    analysisAccessGuard = mock(AnalysisAccessGuard.class);
    requesterContext = mock(RequesterContext.class);
    when(requesterContext.current()).thenReturn(GOOGLER);

    controller =
        new InputController(
            driveResolveService,
            youTubeResolveService,
            submitAnalysisService,
            listAnalysesService,
            getAnalysisService,
            cancelAnalysisService,
            deleteAnalysisService,
            analysisAccessGuard,
            requesterContext);
  }

  /** The browser is not a trustworthy source for the field that attributes the Boq call. */
  @Test
  void submitOverwritesAnyClientSuppliedRequesterId() {
    SubmitAnalysisRequest req =
        SubmitAnalysisRequest.builder().analysisName("Test").requesterId("default-user").build();
    when(submitAnalysisService.execute(any(SubmitAnalysisRequest.class)))
        .thenReturn(Mono.just(ResponseEntity.ok("ana-123")));

    StepVerifier.create(controller.submitAnalysis(req))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("ana-123"))
        .verifyComplete();

    assertThat(req.getRequesterId()).isEqualTo("jdoe");
    verify(submitAnalysisService).execute(req);
  }

  @Test
  void submitIsRejectedWhenTheCallerCannotBeAttributed() {
    when(requesterContext.current()).thenReturn(Requester.unknown());
    SubmitAnalysisRequest req = SubmitAnalysisRequest.builder().analysisName("Test").build();

    StepVerifier.create(controller.submitAnalysis(req))
        .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED))
        .verifyComplete();

    verify(submitAnalysisService, never()).execute(any(SubmitAnalysisRequest.class));
  }

  @Test
  void listUsesTheVerifiedCallerRatherThanAPathVariable() {
    AnalysisRequestEntity a1 = AnalysisRequestEntity.builder().analysisId("1").build();
    when(listAnalysesService.execute("jdoe")).thenReturn(Flux.just(a1));

    StepVerifier.create(controller.listAnalyses()).expectNext(a1).verifyComplete();
    verify(listAnalysesService).execute("jdoe");
  }

  @Test
  void listReturnsNothingForAnUnattributedCaller() {
    when(requesterContext.current()).thenReturn(Requester.unknown());

    StepVerifier.create(controller.listAnalyses()).verifyComplete();
    verify(listAnalysesService, never()).execute(anyString());
  }

  @Test
  void getAnalysisDelegatesWhenTheGuardAllows() {
    AnalysisRequestEntity a1 = AnalysisRequestEntity.builder().analysisId("1").build();
    allowAccessTo("1");
    when(getAnalysisService.execute("1")).thenReturn(Mono.just(ResponseEntity.ok(a1)));

    StepVerifier.create(controller.getAnalysis("1"))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo(a1))
        .verifyComplete();
  }

  @Test
  void getAnalysisReturns404WhenTheGuardDenies() {
    denyAccessTo("1");

    StepVerifier.create(controller.getAnalysis("1"))
        .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
        .verifyComplete();
    verify(getAnalysisService, never()).execute(anyString());
  }

  @Test
  void cancelAnalysisDelegatesWhenTheGuardAllows() {
    allowAccessTo("1");
    when(cancelAnalysisService.execute("1")).thenReturn(Mono.just(ResponseEntity.ok("Cancelled")));

    StepVerifier.create(controller.cancelAnalysis("1"))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("Cancelled"))
        .verifyComplete();
  }

  @Test
  void cancelAnalysisReturns404WhenTheGuardDenies() {
    denyAccessTo("1");

    StepVerifier.create(controller.cancelAnalysis("1"))
        .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
        .verifyComplete();
    verify(cancelAnalysisService, never()).execute(anyString());
  }

  @Test
  void deleteAnalysisDelegatesWhenTheGuardAllows() {
    allowAccessTo("1");
    when(deleteAnalysisService.execute("1")).thenReturn(Mono.just(ResponseEntity.ok("Deleted")));

    StepVerifier.create(controller.deleteAnalysis("1"))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("Deleted"))
        .verifyComplete();
  }

  @Test
  void deleteAnalysisReturns404WhenTheGuardDenies() {
    denyAccessTo("1");

    StepVerifier.create(controller.deleteAnalysis("1"))
        .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND))
        .verifyComplete();
    verify(deleteAnalysisService, never()).execute(anyString());
  }

  @Test
  void resolveDriveDelegatesToService() {
    Map<String, String> body = Map.of("url", "https://drive.google.com/folder");
    @SuppressWarnings("unchecked")
    ResponseEntity<Object> expectedResponse =
        (ResponseEntity<Object>) (ResponseEntity<?>) ResponseEntity.ok("resolved");
    when(driveResolveService.execute(body)).thenReturn(Mono.just(expectedResponse));

    StepVerifier.create(controller.resolveDrive(body))
        .assertNext(resp -> assertThat(resp.getBody()).isEqualTo("resolved"))
        .verifyComplete();
    verify(driveResolveService).execute(body);
  }

  @Test
  void resolveYouTubeDelegatesToService() {
    Map<String, List<String>> body = Map.of("urls", List.of("https://youtube.com/watch?v=123"));
    YouTubeVideoInfoDto info =
        YouTubeVideoInfoDto.builder()
            .videoId("123")
            .url("https://youtube.com/watch?v=123")
            .title("Test Video")
            .unlisted(true)
            .build();
    when(youTubeResolveService.resolveVideos(List.of("https://youtube.com/watch?v=123")))
        .thenReturn(Flux.just(info));

    StepVerifier.create(controller.resolveYouTube(body))
        .assertNext(
            resp -> {
              assertThat(resp.getVideoId()).isEqualTo("123");
              assertThat(resp.isUnlisted()).isTrue();
              assertThat(resp.getTitle()).isEqualTo("Test Video");
            })
        .verifyComplete();
    verify(youTubeResolveService).resolveVideos(List.of("https://youtube.com/watch?v=123"));
  }

  private void allowAccessTo(String analysisId) {
    when(analysisAccessGuard.requireAccess(analysisId, "jdoe"))
        .thenReturn(
            Mono.just(AnalysisRequestEntity.builder().analysisId(analysisId).build()));
  }

  private void denyAccessTo(String analysisId) {
    when(analysisAccessGuard.requireAccess(analysisId, "jdoe")).thenReturn(Mono.empty());
  }
}
