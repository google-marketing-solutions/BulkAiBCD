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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bulkaibcd.model.UploadStatusResponse;
import com.bulkaibcd.model.UploadUnlistedVideosRequest;
import com.bulkaibcd.model.UploadUnlistedVideosResponse;
import com.bulkaibcd.proto.GetAnalysisProgressRequest;
import com.bulkaibcd.proto.GetAnalysisProgressResponse;
import com.bulkaibcd.proto.GetUploadStatusRequest;
import com.bulkaibcd.proto.GetUploadStatusResponse;
import com.bulkaibcd.proto.InputServiceGrpc;
import com.bulkaibcd.proto.UploadStatus;
import com.bulkaibcd.proto.UploadUnlistedVideosToGcsRequest;
import com.bulkaibcd.proto.UploadUnlistedVideosToGcsResponse;
import com.bulkaibcd.proto.VideoUploadStatusInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoqHybridApiClientTest {

  private Server server;
  private ManagedChannel channel;
  private BoqHybridApiClient client;
  private TestInputServiceImpl serviceImpl;

  static class TestInputServiceImpl extends InputServiceGrpc.InputServiceImplBase {
    AtomicReference<UploadUnlistedVideosToGcsRequest> lastUploadRequest = new AtomicReference<>();
    AtomicReference<GetUploadStatusRequest> lastStatusRequest = new AtomicReference<>();
    AtomicReference<GetAnalysisProgressRequest> lastProgressRequest = new AtomicReference<>();
    boolean throwUnavailable = false;
    boolean throwInternal = false;

    @Override
    public void uploadUnlistedVideosToGcs(
        UploadUnlistedVideosToGcsRequest request,
        StreamObserver<UploadUnlistedVideosToGcsResponse> responseObserver) {
      lastUploadRequest.set(request);
      if (throwUnavailable) {
        responseObserver.onError(Status.UNAVAILABLE.withDescription("Service unavailable").asRuntimeException());
        return;
      }
      UploadUnlistedVideosToGcsResponse response =
          UploadUnlistedVideosToGcsResponse.newBuilder()
              .setRequestId("batch-req-123")
              .setQueuedCount(2)
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }

    @Override
    public void getUploadStatus(
        GetUploadStatusRequest request,
        StreamObserver<GetUploadStatusResponse> responseObserver) {
      lastStatusRequest.set(request);
      if (throwInternal) {
        responseObserver.onError(Status.INTERNAL.withDescription("Internal error").asRuntimeException());
        return;
      }
      GetUploadStatusResponse response =
          GetUploadStatusResponse.newBuilder()
              .setRequestId(request.getRequestId())
              .setTotalCount(2)
              .setCompletedCount(2)
              .setFailedCount(0)
              .setInProgressCount(0)
              .setAllCompleted(true)
              .addVideos(
                  VideoUploadStatusInfo.newBuilder()
                      .setVideoId("vid1")
                      .setUploadStatus(UploadStatus.UPLOAD_COMPLETED)
                      .build())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }

    @Override
    public void getAnalysisProgress(
        GetAnalysisProgressRequest request,
        StreamObserver<GetAnalysisProgressResponse> responseObserver) {
      lastProgressRequest.set(request);
      GetAnalysisProgressResponse response =
          GetAnalysisProgressResponse.newBuilder()
              .setProgressPercentage(75)
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  @BeforeEach
  void setUp() throws IOException {
    String serverName = InProcessServerBuilder.generateName();
    serviceImpl = new TestInputServiceImpl();
    server = InProcessServerBuilder.forName(serverName).directExecutor().addService(serviceImpl).build().start();
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    InputServiceGrpc.InputServiceBlockingStub stub = InputServiceGrpc.newBlockingStub(channel);
    client = new BoqHybridApiClient(stub, new ObjectMapper());
  }

  @AfterEach
  void tearDown() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.shutdownNow();
    }
  }

  @Test
  void uploadUnlistedVideosToGcsSendsExpectedPayloadAndParsesResponse() {
    UploadUnlistedVideosRequest request =
        UploadUnlistedVideosRequest.builder()
            .unlistedYoutubeVideoIds(List.of("vid1", "vid2"))
            .gcsUriPrefix("gs://my-bucket/unlisted/")
            .userId("user1")
            .analysisName("test-analysis")
            .build();

    UploadUnlistedVideosResponse result = client.uploadUnlistedVideosToGcs(request);

    assertThat(result).isNotNull();
    assertThat(result.getRequestId()).isEqualTo("batch-req-123");

    UploadUnlistedVideosToGcsRequest captured = serviceImpl.lastUploadRequest.get();
    assertThat(captured).isNotNull();
    assertThat(captured.getGcsUriPrefix()).isEqualTo("gs://my-bucket/unlisted/");
    assertThat(captured.getUserId()).isEqualTo("user1");
    assertThat(captured.getAnalysisName()).isEqualTo("test-analysis");
    assertThat(captured.getUnlistedYoutubeVideoIdsList()).containsExactly("vid1", "vid2");
  }

  @Test
  void getUploadStatusSendsExpectedPayloadAndParsesResponse() {
    UploadStatusResponse result = client.getUploadStatus("batch-req-123");

    assertThat(result).isNotNull();
    assertThat(result.isAllCompleted()).isTrue();
    assertThat(result.getTotalCount()).isEqualTo(2);
    assertThat(result.getCompletedCount()).isEqualTo(2);

    GetUploadStatusRequest captured = serviceImpl.lastStatusRequest.get();
    assertThat(captured).isNotNull();
    assertThat(captured.getRequestId()).isEqualTo("batch-req-123");
  }

  @Test
  void getAnalysisProgressReturnsProgressPercentage() {
    int progress = client.getAnalysisProgress("analysis-123");

    assertThat(progress).isEqualTo(75);

    GetAnalysisProgressRequest captured = serviceImpl.lastProgressRequest.get();
    assertThat(captured).isNotNull();
    assertThat(captured.getAnalysisId()).isEqualTo("analysis-123");
  }

  @Test
  void uploadUnlistedVideosToGcsThrowsWhenGrpcErrorOccurs() {
    serviceImpl.throwUnavailable = true;

    UploadUnlistedVideosRequest request =
        UploadUnlistedVideosRequest.builder()
            .unlistedYoutubeVideoIds(List.of("vid1"))
            .userId("jdoe")
            .build();

    assertThatThrownBy(() -> client.uploadUnlistedVideosToGcs(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("UNAVAILABLE");
  }

  /**
   * The Boq backend attributes its access log to user_id, so a request that cannot name a requester
   * must never reach the wire.
   */
  @Test
  void uploadUnlistedVideosToGcsRefusesAnUnattributedRequest() {
    UploadUnlistedVideosRequest request =
        UploadUnlistedVideosRequest.builder()
            .unlistedYoutubeVideoIds(List.of("vid1"))
            .build();

    assertThatThrownBy(() -> client.uploadUnlistedVideosToGcs(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("without a requester");

    assertThat(serviceImpl.lastUploadRequest.get()).isNull();
  }

  @Test
  void getUploadStatusThrowsWhenGrpcErrorOccurs() {
    serviceImpl.throwInternal = true;

    assertThatThrownBy(() -> client.getUploadStatus("batch-123"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("INTERNAL");
  }
}
