package com.bulkaibcd.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.SubmitAnalysisRequest;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityMapperTest {

  @Test
  void toAnalysisRequestEntityWithAllFields() {
    SubmitAnalysisRequest req =
        SubmitAnalysisRequest.builder()
            .requesterId("user-1")
            .analysisName("Q3 ABCD")
            .analysisType("standard")
            .brandName("Acme")
            .marketingObjective("awareness")
            .customFeaturesLong(List.of("f1"))
            .customFeaturesShort(List.of("f2"))
            .build();

    AnalysisRequestEntity entity = EntityMapper.toAnalysisRequestEntity(req);
    assertThat(entity.getAnalysisId()).isNotBlank();
    assertThat(entity.getRequesterId()).isEqualTo("user-1");
    assertThat(entity.getAnalysisName()).isEqualTo("Q3 ABCD");
    assertThat(entity.getAnalysisType()).isEqualTo("standard");
    assertThat(entity.getBrandName()).isEqualTo("Acme");
    assertThat(entity.getMarketingObjective()).isEqualTo("awareness");
    assertThat(entity.getCustomFeaturesLong()).containsExactly("f1");
    assertThat(entity.getCustomFeaturesShort()).containsExactly("f2");
    assertThat(entity.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING.name());
    assertThat(entity.getCreatedAt()).isNotNull();
    assertThat(entity.getUpdatedAt()).isNotNull();
  }

  @Test
  void toVideoInputEntity() {
    SubmitAnalysisRequest.VideoInput v1 =
        SubmitAnalysisRequest.VideoInput.builder()
            .sourceType("youtube")
            .videoName("Vid 1")
            .videoUrl("https://yt.com/1")
            .thumbnailUrl("thumb1")
            .format("SHORT")
            .gcsObjectId("gcs-1")
            .build();

    VideoInputEntity e1 = EntityMapper.toVideoInputEntity(v1, "analysis-123");
    assertThat(e1.getId()).isEqualTo("analysis-123_" + e1.getVideoId());
    assertThat(e1.getAnalysisId()).isEqualTo("analysis-123");
    assertThat(e1.getVideoName()).isEqualTo("Vid 1");
    assertThat(e1.getVideoUrl()).isEqualTo("https://yt.com/1");
    assertThat(e1.getThumbnailUrl()).isEqualTo("thumb1");
    assertThat(e1.getSourceType()).isEqualTo("youtube");
    assertThat(e1.getFormat()).isEqualTo("SHORT");
    assertThat(e1.getGcsObjectId()).isEqualTo("gcs-1");
  }

  @Test
  void toVideoMetadataEntityFromVideoInputEntity() {
    VideoInputEntity input1 =
        VideoInputEntity.builder()
            .analysisId("ana-1")
            .videoId("v1")
            .videoName("Name 1")
            .videoUrl("https://url.com/1")
            .gcsObjectId("gcs-1")
            .thumbnailUrl("thumb1")
            .sourceType("youtube")
            .format("LONG")
            .errorMessage("something failed")
            .build();

    VideoMetadataEntity m1 = EntityMapper.toVideoMetadataEntity(input1, false);
    assertThat(m1.getId()).isEqualTo("ana-1_v1");
    assertThat(m1.getStatus()).isEqualTo(AnalysisStatus.PROCESSING.name());
    assertThat(m1.getVideoName()).isEqualTo("Name 1");
    assertThat(m1.getVideoUrl()).isEqualTo("https://url.com/1");
    assertThat(m1.getGcsObjectId()).isEqualTo("gcs-1");
    assertThat(m1.getThumbnailUrl()).isEqualTo("thumb1");
    assertThat(m1.getSourceType()).isEqualTo("youtube");
    assertThat(m1.getFormat()).isEqualTo("LONG");

    VideoMetadataEntity errorMeta = EntityMapper.toVideoMetadataEntity(input1, true);
    assertThat(errorMeta.getStatus()).isEqualTo(AnalysisStatus.COMPLETED.name());
    assertThat(errorMeta.getErrorMessage()).isEqualTo("something failed");
    assertThat(errorMeta.getAScore()).isEqualTo(0);
  }

  @Test
  void toVideoMetadataEntityFromAnalysisAndVideoId() {
    VideoMetadataEntity metadata = EntityMapper.toVideoMetadataEntity("ana-x", "vid-y");
    assertThat(metadata.getId()).isEqualTo("ana-x_vid-y");
    assertThat(metadata.getAnalysisId()).isEqualTo("ana-x");
    assertThat(metadata.getVideoId()).isEqualTo("vid-y");
    assertThat(metadata.getStatus()).isEqualTo(AnalysisStatus.PROCESSING.name());
  }
}
