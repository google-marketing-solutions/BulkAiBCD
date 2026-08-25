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

package com.bulkaibcd.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.BoqInputServiceClient;
import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.UploadStatusResponse;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoUploadStatusInfo;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CheckUploadStatusServiceTest {

  private BoqInputServiceClient boqInputServiceClient;
  private VideoInputRepository videoInputRepository;
  private AnalysisRequestRepository analysisRequestRepository;
  private VideoMetadataRepository videoMetadataRepository;
  private BatchPredictionOrchestrator batchPredictionOrchestrator;
  private CloudTasksQueueClient cloudTasksQueueClient;
  private CheckUploadStatusService service;

  @BeforeEach
  void setUp() {
    boqInputServiceClient = mock(BoqInputServiceClient.class);
    videoInputRepository = mock(VideoInputRepository.class);
    analysisRequestRepository = mock(AnalysisRequestRepository.class);
    videoMetadataRepository = mock(VideoMetadataRepository.class);
    batchPredictionOrchestrator = mock(BatchPredictionOrchestrator.class);
    cloudTasksQueueClient = mock(CloudTasksQueueClient.class);

    service =
        new CheckUploadStatusService(
            boqInputServiceClient,
            videoInputRepository,
            analysisRequestRepository,
            videoMetadataRepository,
            batchPredictionOrchestrator,
            cloudTasksQueueClient);
  }

  @Test
  void executeWhenUploadInProgressReenqueuesPollTask() throws Exception {
    UploadStatusResponse inProgress =
        UploadStatusResponse.builder()
            .totalCount(2)
            .completedCount(1)
            .allCompleted(false)
            .build();
    when(boqInputServiceClient.getUploadStatus("batch-123")).thenReturn(inProgress);

    Map<String, Object> payload =
        Map.of("analysisId", "ana-1", "requestId", "batch-123", "attemptCount", 1);

    StepVerifier.create(service.execute(payload))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).contains("in progress");
            })
        .verifyComplete();

    verify(cloudTasksQueueClient)
        .enqueueTask(
            eq("/api/v2/worker/check-upload-status"),
            eq("{\"analysisId\":\"ana-1\",\"requestId\":\"batch-123\",\"attemptCount\":2}"),
            eq("ana-1_UPLOAD_POLL_attempt_2"),
            eq(20));
  }

  @Test
  void executeWhenMaxAttemptsExceededMarksAnalysisFailed() {
    UploadStatusResponse inProgress =
        UploadStatusResponse.builder().totalCount(2).allCompleted(false).build();
    when(boqInputServiceClient.getUploadStatus("batch-123")).thenReturn(inProgress);

    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").analysisStatus("PROCESSING").build();
    when(analysisRequestRepository.findById("ana-1")).thenReturn(Mono.just(parent));
    when(analysisRequestRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    Map<String, Object> payload =
        Map.of("analysisId", "ana-1", "requestId", "batch-123", "attemptCount", 60);

    StepVerifier.create(service.execute(payload))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.REQUEST_TIMEOUT);
            })
        .verifyComplete();

    assertThat(parent.getAnalysisStatus()).isEqualTo("FAILED");
  }

  @Test
  void executeWhenAllCompletedUpdatesGcsObjectIdAndLaunchesPhase1() throws Exception {
    UploadStatusResponse completed =
        UploadStatusResponse.builder()
            .totalCount(1)
            .completedCount(1)
            .allCompleted(true)
            .videoUploadStatuses(
                List.of(
                    VideoUploadStatusInfo.builder()
                        .videoId("dQw4w9WgXcQ")
                        .status("UPLOAD_COMPLETED")
                        .gcsPath("gs://my-bucket/unlisted_ana-1/dQw4w9WgXcQ.mp4")
                        .build()))
            .build();
    when(boqInputServiceClient.getUploadStatus("batch-123")).thenReturn(completed);

    VideoInputEntity unlistedVid =
        VideoInputEntity.builder()
            .id("ana-1_v1")
            .analysisId("ana-1")
            .videoId("v1")
            .sourceType("YOUTUBE")
            .videoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            .unlisted(true)
            .build();

    when(videoInputRepository.findByAnalysisId("ana-1")).thenReturn(Flux.just(unlistedVid));
    when(videoInputRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(videoMetadataRepository.findById("ana-1_v1")).thenReturn(Mono.empty());
    when(videoMetadataRepository.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(batchPredictionOrchestrator.submitPhase1Job(eq("ana-1"), any())).thenReturn("batch-p1-123");

    Map<String, Object> payload =
        Map.of("analysisId", "ana-1", "requestId", "batch-123", "attemptCount", 2);

    StepVerifier.create(service.execute(payload))
        .assertNext(
            resp -> {
              assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
              assertThat(resp.getBody()).contains("Phase 1 batch launched");
            })
        .verifyComplete();

    assertThat(unlistedVid.getGcsObjectId()).isEqualTo("my-bucket/unlisted_ana-1/dQw4w9WgXcQ.mp4");
    verify(batchPredictionOrchestrator).submitPhase1Job(eq("ana-1"), any());
    verify(cloudTasksQueueClient)
        .enqueueTask(
            eq("/api/v2/worker/check-phase1-status"),
            anyString(),
            eq("ana-1_P1_POLL_attempt_1"),
            eq(300));
  }
}
