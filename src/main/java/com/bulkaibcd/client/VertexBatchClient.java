package com.bulkaibcd.client;

import com.google.cloud.aiplatform.v1.BatchPredictionJob;
import com.google.cloud.aiplatform.v1.CreateBatchPredictionJobRequest;
import com.google.cloud.aiplatform.v1.GcsDestination;
import com.google.cloud.aiplatform.v1.GcsSource;
import com.google.cloud.aiplatform.v1.JobServiceClient;
import com.google.cloud.aiplatform.v1.JobServiceSettings;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Client gateway responsible for encapsulating Vertex AI JobServiceClient operations.
 *
 * <p>Manages regional endpoint configuration, dispatching CreateBatchPredictionJob requests, and
 * querying BatchPredictionJob state.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class VertexBatchClient {

  @Value("${google.cloud.project.id}")
  private String projectId;

  @Value("${google.cloud.location:us-central1}")
  private String location;

  public String createBatchPredictionJob(
      String jobDisplayName, String modelResourceName, String inputGcsUri, String outputGcsPrefix)
      throws IOException {
    String regionEndpoint = location + "-aiplatform.googleapis.com:443";
    log.info("VertexBatchClient: Connecting to Vertex AI regional endpoint: {}", regionEndpoint);

    JobServiceSettings settings =
        JobServiceSettings.newBuilder().setEndpoint(regionEndpoint).build();

    try (JobServiceClient client = JobServiceClient.create(settings)) {
      String parent = String.format("projects/%s/locations/%s", projectId, location);

      GcsSource gcsSource = GcsSource.newBuilder().addUris(inputGcsUri).build();
      GcsDestination gcsDestination =
          GcsDestination.newBuilder().setOutputUriPrefix(outputGcsPrefix).build();

      BatchPredictionJob.InputConfig inputConfig =
          BatchPredictionJob.InputConfig.newBuilder()
              .setInstancesFormat("jsonl")
              .setGcsSource(gcsSource)
              .build();

      BatchPredictionJob.OutputConfig outputConfig =
          BatchPredictionJob.OutputConfig.newBuilder()
              .setPredictionsFormat("jsonl")
              .setGcsDestination(gcsDestination)
              .build();

      BatchPredictionJob batchPredictionJob =
          BatchPredictionJob.newBuilder()
              .setDisplayName(jobDisplayName)
              .setModel(modelResourceName)
              .setInputConfig(inputConfig)
              .setOutputConfig(outputConfig)
              .build();

      CreateBatchPredictionJobRequest request =
          CreateBatchPredictionJobRequest.newBuilder()
              .setParent(parent)
              .setBatchPredictionJob(batchPredictionJob)
              .build();

      log.info(
          "VertexBatchClient: Sending createBatchPredictionJob REST call for {}", jobDisplayName);
      BatchPredictionJob launchedJob = client.createBatchPredictionJob(request);
      String batchJobId = launchedJob.getName();
      log.info(
          "VertexBatchClient: Vertex Batch Prediction successfully launched! Resource Job Name ID:"
              + " {}",
          batchJobId);
      return batchJobId;
    }
  }

  public BatchPredictionJob getBatchPredictionJob(String batchJobId) throws IOException {
    String regionEndpoint = location + "-aiplatform.googleapis.com:443";
    JobServiceSettings settings =
        JobServiceSettings.newBuilder().setEndpoint(regionEndpoint).build();
    try (JobServiceClient client = JobServiceClient.create(settings)) {
      return client.getBatchPredictionJob(batchJobId);
    }
  }
}
