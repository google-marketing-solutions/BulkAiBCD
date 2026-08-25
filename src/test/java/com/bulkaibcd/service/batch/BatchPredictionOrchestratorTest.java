package com.bulkaibcd.service.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.client.GcsClient;
import com.bulkaibcd.client.VertexBatchClient;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.FeatureParameter;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.FeatureConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.WriteChannel;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

class BatchPredictionOrchestratorTest {

  private GcsClient gcsStorage;
  private VertexBatchClient vertexBatchClient;
  private PromptBuilderService promptBuilder;
  private DynamicScoringService dynamicScoring;
  private FeatureConfigService featureConfigService;
  private VideoMetadataRepository videoMetadataRepo;
  private AnalysisRequestRepository analysisRepo;
  private VideoInputRepository videoInputRepo;
  private CloudTasksQueueClient cloudTasksClient;
  private ObjectMapper objectMapper;

  private BatchPredictionOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    gcsStorage = mock(GcsClient.class);
    vertexBatchClient = mock(VertexBatchClient.class);
    promptBuilder = mock(PromptBuilderService.class);
    dynamicScoring = mock(DynamicScoringService.class);
    featureConfigService = mock(FeatureConfigService.class);
    videoMetadataRepo = mock(VideoMetadataRepository.class);
    analysisRepo = mock(AnalysisRequestRepository.class);
    videoInputRepo = mock(VideoInputRepository.class);
    cloudTasksClient = mock(CloudTasksQueueClient.class);
    objectMapper = new ObjectMapper();

    orchestrator =
        new BatchPredictionOrchestrator(
            gcsStorage,
            vertexBatchClient,
            promptBuilder,
            dynamicScoring,
            featureConfigService,
            videoMetadataRepo,
            analysisRepo,
            videoInputRepo,
            cloudTasksClient,
            objectMapper);

    ReflectionTestUtils.setField(orchestrator, "uploadsBucket", "test-bucket");
    ReflectionTestUtils.setField(orchestrator, "modelName", "gemini-2.5-pro");
  }

  @Test
  void submitPhase1JobReturnsNoValidVideosWhenAllVideosHaveErrors() throws Exception {
    VideoInputEntity v1 =
        VideoInputEntity.builder()
            .id("ana-1_v1")
            .analysisId("ana-1")
            .videoId("v1")
            .errorMessage("Error")
            .build();

    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));
    when(analysisRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    String result = orchestrator.submitPhase1Job("ana-1", List.of(v1));
    assertThat(result).isEqualTo("NO_VALID_VIDEOS");
  }

  @Test
  void submitPhase1JobWritesJsonlAndCreatesVertexJob() throws Exception {
    VideoInputEntity v1 =
        VideoInputEntity.builder()
            .id("ana-1_v1")
            .analysisId("ana-1")
            .videoId("v1")
            .videoUrl("https://youtube.com/1")
            .build();

    WriteChannel mockChannel = mock(WriteChannel.class);
    when(gcsStorage.createWriter(eq("test-bucket"), anyString(), eq("application/jsonl")))
        .thenReturn(mockChannel);
    when(promptBuilder.createPhase1RequestPayloadRow(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn("{\"instance\":\"1\"}");

    when(vertexBatchClient.createBatchPredictionJob(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("batch-job-1");

    String result = orchestrator.submitPhase1Job("ana-1", List.of(v1));
    assertThat(result).isEqualTo("batch-job-1");
  }

  @Test
  void submitPhase2JobWritesJsonlAndLaunchesVertexJob() throws Exception {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().analysisId("ana-1").build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(parent));

    VideoMetadataEntity v1 =
        VideoMetadataEntity.builder()
            .id("ana-1_v1")
            .analysisId("ana-1")
            .videoId("v1")
            .videoUrl("https://youtube.com/1")
            .format("LONG")
            .build();

    FeatureParameter fp1 =
        FeatureParameter.builder().id("a_first_5_secs").name("Brand First 5s").build();
    when(featureConfigService.getFeaturesByTypeAndFormat(eq("standard"), any()))
        .thenReturn(List.of(fp1));

    WriteChannel mockChannel = mock(WriteChannel.class);
    when(gcsStorage.createWriter(eq("test-bucket"), anyString(), eq("application/jsonl")))
        .thenReturn(mockChannel);

    when(promptBuilder.createMetadataSummary(v1)).thenReturn("Summary");
    when(promptBuilder.generateSingleFeaturePrompt(eq(fp1), eq(v1), anyString()))
        .thenReturn("Prompt text");
    when(promptBuilder.createRequestPayloadRow(anyString(), anyString(), anyString()))
        .thenReturn("{\"instance\":\"2\"}");

    when(vertexBatchClient.createBatchPredictionJob(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("batch-job-2");

    String batchJobId = orchestrator.submitPhase2Job("ana-1", List.of(v1), "standard", "Acme");
    assertThat(batchJobId).isEqualTo("batch-job-2");
  }
}
