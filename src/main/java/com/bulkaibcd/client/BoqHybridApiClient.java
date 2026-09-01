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

package com.bulkaibcd.client;

import com.bulkaibcd.model.UploadStatusRequest;
import com.bulkaibcd.model.UploadStatusResponse;
import com.bulkaibcd.model.UploadUnlistedVideosRequest;
import com.bulkaibcd.model.UploadUnlistedVideosResponse;
import com.bulkaibcd.proto.GetAnalysisProgressRequest;
import com.bulkaibcd.proto.GetAnalysisProgressResponse;
import com.bulkaibcd.proto.GetUploadStatusRequest;
import com.bulkaibcd.proto.GetUploadStatusResponse;
import com.bulkaibcd.proto.InputServiceGrpc;
import com.bulkaibcd.proto.UploadUnlistedVideosToGcsRequest;
import com.bulkaibcd.proto.UploadUnlistedVideosToGcsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A client implementation for invoking Boq InputService RPCs through the Google Hybrid API gateway over gRPC.
 */
@Component
@Slf4j
public class BoqHybridApiClient implements BoqInputServiceClient {

  private final InputServiceGrpc.InputServiceBlockingStub inputServiceStub;
  private final ObjectMapper objectMapper;

  @Autowired
  public BoqHybridApiClient(
      InputServiceGrpc.InputServiceBlockingStub inputServiceStub,
      ObjectMapper objectMapper) {
    this.inputServiceStub = inputServiceStub;
    this.objectMapper = objectMapper;
  }

  @Override
  public UploadUnlistedVideosResponse uploadUnlistedVideosToGcs(
      UploadUnlistedVideosRequest request) {
    log.info(
        "BoqHybridApiClient: Initiating UploadUnlistedVideosToGcs for {} video(s) (Analysis: '{}', User: '{}', Prefix: '{}', Video IDs: {})",
        request.getUnlistedYoutubeVideoIds() != null ? request.getUnlistedYoutubeVideoIds().size() : 0,
        request.getAnalysisName(),
        request.getUserId(),
        request.getGcsUriPrefix(),
        request.getUnlistedYoutubeVideoIds());

    UploadUnlistedVideosToGcsRequest.Builder protoRequestBuilder =
        UploadUnlistedVideosToGcsRequest.newBuilder()
            .setRequestId(request.getRequestId() != null ? request.getRequestId() : "")
            .setGcsUriPrefix(request.getGcsUriPrefix() != null ? request.getGcsUriPrefix() : "")
            .setUserId(request.getUserId() != null ? request.getUserId() : "default-user")
            .setAnalysisName(request.getAnalysisName() != null ? request.getAnalysisName() : "");

    if (request.getUnlistedYoutubeVideoIds() != null) {
      protoRequestBuilder.addAllUnlistedYoutubeVideoIds(request.getUnlistedYoutubeVideoIds());
    }

    UploadUnlistedVideosToGcsRequest protoRequest = protoRequestBuilder.build();

    try {
      Instant start = Instant.now();
      UploadUnlistedVideosToGcsResponse protoResponse =
          inputServiceStub.uploadUnlistedVideosToGcs(protoRequest);
      long durationMs = Duration.between(start, Instant.now()).toMillis();

      log.info(
          "BoqHybridApiClient: UploadUnlistedVideosToGcs succeeded in {} ms. Assigned batch requestId: {}",
          durationMs,
          protoResponse.getRequestId());

      String jsonResponse = JsonFormat.printer().includingDefaultValueFields().print(protoResponse);
      return objectMapper.readValue(jsonResponse, UploadUnlistedVideosResponse.class);
    } catch (StatusRuntimeException e) {
      log.error(
          "BoqHybridApiClient: gRPC UploadUnlistedVideosToGcs failed with status {}: {}",
          e.getStatus().getCode(),
          e.getStatus().getDescription(),
          e);
      throw new IllegalStateException(
          String.format("Boq RPC failed with gRPC status %s: %s", e.getStatus().getCode(), e.getStatus().getDescription()),
          e);
    } catch (Exception e) {
      log.error("BoqHybridApiClient: Failed to process UploadUnlistedVideosToGcs response", e);
      throw new IllegalStateException("Failed to execute UploadUnlistedVideosToGcs", e);
    }
  }

  @Override
  public UploadStatusResponse getUploadStatus(String requestId) {
    log.info("BoqHybridApiClient: Polling GetUploadStatus for batch requestId: {}", requestId);

    GetUploadStatusRequest protoRequest =
        GetUploadStatusRequest.newBuilder()
            .setRequestId(requestId != null ? requestId : "")
            .build();

    try {
      Instant start = Instant.now();
      GetUploadStatusResponse protoResponse = inputServiceStub.getUploadStatus(protoRequest);
      long durationMs = Duration.between(start, Instant.now()).toMillis();

      log.info(
          "BoqHybridApiClient: GetUploadStatus for requestId: {} completed in {} ms -> allCompleted: {}, completed: {}/{}, inProgress: {}, failed: {}",
          requestId,
          durationMs,
          protoResponse.getAllCompleted(),
          protoResponse.getCompletedCount(),
          protoResponse.getTotalCount(),
          protoResponse.getInProgressCount(),
          protoResponse.getFailedCount());

      String jsonResponse = JsonFormat.printer().includingDefaultValueFields().print(protoResponse);
      return objectMapper.readValue(jsonResponse, UploadStatusResponse.class);
    } catch (StatusRuntimeException e) {
      log.error(
          "BoqHybridApiClient: gRPC GetUploadStatus failed for requestId {} with status {}: {}",
          requestId,
          e.getStatus().getCode(),
          e.getStatus().getDescription(),
          e);
      throw new IllegalStateException(
          String.format("Boq RPC GetUploadStatus failed with gRPC status %s: %s", e.getStatus().getCode(), e.getStatus().getDescription()),
          e);
    } catch (Exception e) {
      log.error("BoqHybridApiClient: Failed to process GetUploadStatus response", e);
      throw new IllegalStateException("Failed to execute GetUploadStatus", e);
    }
  }

  /**
   * Retrieves analysis progress percentage for a given analysis ID.
   *
   * @param analysisId the ID of the analysis
   * @return progress percentage (-1 to 100)
   */
  public int getAnalysisProgress(String analysisId) {
    log.info("BoqHybridApiClient: Calling GetAnalysisProgress for analysisId: {}", analysisId);

    GetAnalysisProgressRequest protoRequest =
        GetAnalysisProgressRequest.newBuilder()
            .setAnalysisId(analysisId != null ? analysisId : "")
            .build();

    try {
      GetAnalysisProgressResponse protoResponse =
          inputServiceStub.getAnalysisProgress(protoRequest);
      return protoResponse.getProgressPercentage();
    } catch (StatusRuntimeException e) {
      log.error(
          "BoqHybridApiClient: gRPC GetAnalysisProgress failed for analysisId {} with status {}: {}",
          analysisId,
          e.getStatus().getCode(),
          e.getStatus().getDescription(),
          e);
      throw new IllegalStateException(
          String.format("Boq RPC GetAnalysisProgress failed with gRPC status %s: %s", e.getStatus().getCode(), e.getStatus().getDescription()),
          e);
    }
  }
}
