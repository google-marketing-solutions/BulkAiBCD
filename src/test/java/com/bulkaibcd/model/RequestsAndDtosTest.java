package com.bulkaibcd.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RequestsAndDtosTest {

  @Test
  void testGenerateDeckRequest() {
    GenerateDeckRequest req =
        GenerateDeckRequest.builder()
            .analysisId("ana-1")
            .userAccessToken("user-token")
            .legacyAccessToken("legacy-token")
            .videoIds(List.of("v1", "v2"))
            .build();

    assertThat(req.getAnalysisId()).isEqualTo("ana-1");
    assertThat(req.getUserAccessToken()).isEqualTo("user-token");
    assertThat(req.getLegacyAccessToken()).isEqualTo("legacy-token");
    assertThat(req.getVideoIds()).containsExactly("v1", "v2");
    assertThat(req.toString()).contains("ana-1");
  }

  @Test
  void testGenerateReportRequest() {
    GenerateReportRequest req =
        GenerateReportRequest.builder()
            .analysisId("ana-2")
            .userAccessToken("u-token")
            .legacyAccessToken("l-token")
            .build();

    assertThat(req.getAnalysisId()).isEqualTo("ana-2");
    assertThat(req.getUserAccessToken()).isEqualTo("u-token");
    assertThat(req.getLegacyAccessToken()).isEqualTo("l-token");
  }

  @Test
  void testGoogleAdsCampaignDto() {
    GoogleAdsCampaignDto dto =
        GoogleAdsCampaignDto.builder()
            .id("c-1")
            .name("Campaign 1")
            .status("ENABLED")
            .build();

    assertThat(dto.getId()).isEqualTo("c-1");
    assertThat(dto.getName()).isEqualTo("Campaign 1");
    assertThat(dto.getStatus()).isEqualTo("ENABLED");
  }

  @Test
  void testGoogleAdsConfigDto() {
    GoogleAdsConfigDto dto =
        GoogleAdsConfigDto.builder()
            .developerToken("dev-tok")
            .clientId("client-id")
            .clientSecret("client-sec")
            .frontendOrigin("http://localhost:4200")
            .build();

    assertThat(dto.getDeveloperToken()).isEqualTo("dev-tok");
    assertThat(dto.getClientId()).isEqualTo("client-id");
    assertThat(dto.getClientSecret()).isEqualTo("client-sec");
    assertThat(dto.getFrontendOrigin()).isEqualTo("http://localhost:4200");
  }

  @Test
  void testGoogleAdsStatusDto() {
    GoogleAdsStatusDto dto =
        GoogleAdsStatusDto.builder().configured(true).authorized(true).build();

    assertThat(dto.isConfigured()).isTrue();
    assertThat(dto.isAuthorized()).isTrue();
  }

  @Test
  void testGoogleAdsVideoAssetDto() {
    GoogleAdsVideoAssetDto dto =
        GoogleAdsVideoAssetDto.builder()
            .id("asset-1")
            .name("Video Asset 1")
            .youtubeVideoId("yt-123")
            .build();

    assertThat(dto.getId()).isEqualTo("asset-1");
    assertThat(dto.getName()).isEqualTo("Video Asset 1");
    assertThat(dto.getYoutubeVideoId()).isEqualTo("yt-123");
  }

  @Test
  void testGuidelineRelevance() {
    GuidelineRelevance gr =
        GuidelineRelevance.builder()
            .parameterId("p1")
            .core(1)
            .awareness(2)
            .consideration(3)
            .action(4)
            .build();

    assertThat(gr.getParameterId()).isEqualTo("p1");
    assertThat(gr.getCore()).isEqualTo(1);
    assertThat(gr.getAwareness()).isEqualTo(2);
    assertThat(gr.getConsideration()).isEqualTo(3);
    assertThat(gr.getAction()).isEqualTo(4);
  }

  @Test
  void testNotDetectedFeatureEntity() {
    NotDetectedFeatureEntity entity =
        new NotDetectedFeatureEntity("Logo Presence", "No logo visible in first 5 seconds");

    assertThat(entity.getFeature()).isEqualTo("Logo Presence");
    assertThat(entity.getRationale()).isEqualTo("No logo visible in first 5 seconds");

    entity.setFeature("New Feature");
    entity.setRationale("New Rationale");
    assertThat(entity.getFeature()).isEqualTo("New Feature");
    assertThat(entity.getRationale()).isEqualTo("New Rationale");
  }

  @Test
  void testPollRequest() {
    PollRequest req = new PollRequest();
    req.setAnalysisId("ana-poll");
    req.setBatchJobId("job-123");
    req.setAttemptCount(3);

    assertThat(req.getAnalysisId()).isEqualTo("ana-poll");
    assertThat(req.getBatchJobId()).isEqualTo("job-123");
    assertThat(req.getAttemptCount()).isEqualTo(3);
  }

  @Test
  void testProcessRequest() {
    ProcessRequest req = new ProcessRequest();
    req.setAnalysisId("ana-proc");
    req.setGcsUri("gs://bucket/predictions.jsonl");

    assertThat(req.getAnalysisId()).isEqualTo("ana-proc");
    assertThat(req.getGcsUri()).isEqualTo("gs://bucket/predictions.jsonl");
  }

  @Test
  void testTaskRequest() {
    TaskRequest req = new TaskRequest();
    req.setExecutionCount(1);
    req.setAnalysisId("ana-task");
    req.setVideoId("vid-1");
    req.setVideoUri("gs://bucket/vid.mp4");
    req.setPromptType("BRAND");

    assertThat(req.getExecutionCount()).isEqualTo(1);
    assertThat(req.getAnalysisId()).isEqualTo("ana-task");
    assertThat(req.getVideoId()).isEqualTo("vid-1");
    assertThat(req.getVideoUri()).isEqualTo("gs://bucket/vid.mp4");
    assertThat(req.getPromptType()).isEqualTo("BRAND");
  }

  @Test
  void testSubmitAnalysisRequest() {
    SubmitAnalysisRequest.VideoInput videoInput =
        SubmitAnalysisRequest.VideoInput.builder()
            .sourceType("youtube")
            .videoName("Sample Video")
            .videoUrl("https://youtube.com/watch?v=123")
            .gcsObjectId("uploads/vid.mp4")
            .thumbnailUrl("data:image/jpeg;base64,...")
            .format("LONG")
            .build();

    SubmitAnalysisRequest req =
        SubmitAnalysisRequest.builder()
            .requesterId("user-1")
            .analysisName("My Analysis")
            .analysisType("standard")
            .brandName("Google")
            .marketingObjective("awareness")
            .customFeaturesLong(List.of("feature1"))
            .customFeaturesShort(List.of("feature2"))
            .videos(List.of(videoInput))
            .build();

    assertThat(req.getRequesterId()).isEqualTo("user-1");
    assertThat(req.getAnalysisName()).isEqualTo("My Analysis");
    assertThat(req.getAnalysisType()).isEqualTo("standard");
    assertThat(req.getBrandName()).isEqualTo("Google");
    assertThat(req.getMarketingObjective()).isEqualTo("awareness");
    assertThat(req.getCustomFeaturesLong()).containsExactly("feature1");
    assertThat(req.getCustomFeaturesShort()).containsExactly("feature2");
    assertThat(req.getVideos()).hasSize(1);
    assertThat(req.getVideos().get(0).getVideoName()).isEqualTo("Sample Video");
    assertThat(req.getVideos().get(0).getFormat()).isEqualTo("LONG");
  }
}
