package com.bulkaibcd.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VideoInputEntityTest {

  @Test
  void testVideoInputEntityBuilderAndGetters() {
    VideoInputEntity entity =
        VideoInputEntity.builder()
            .id("ana-1_vid-1")
            .analysisId("ana-1")
            .videoId("vid-1")
            .videoName("Test Video")
            .videoUrl("https://youtube.com/watch?v=123")
            .thumbnailUrl("data:image/jpeg;base64,...")
            .sourceType("youtube")
            .format("LONG")
            .gcsObjectId("uploads/vid-1.mp4")
            .errorMessage("Error")
            .build();

    assertThat(entity.getId()).isEqualTo("ana-1_vid-1");
    assertThat(entity.getAnalysisId()).isEqualTo("ana-1");
    assertThat(entity.getVideoId()).isEqualTo("vid-1");
    assertThat(entity.getVideoName()).isEqualTo("Test Video");
    assertThat(entity.getVideoUrl()).isEqualTo("https://youtube.com/watch?v=123");
    assertThat(entity.getThumbnailUrl()).isEqualTo("data:image/jpeg;base64,...");
    assertThat(entity.getSourceType()).isEqualTo("youtube");
    assertThat(entity.getFormat()).isEqualTo("LONG");
    assertThat(entity.getGcsObjectId()).isEqualTo("uploads/vid-1.mp4");
    assertThat(entity.getErrorMessage()).isEqualTo("Error");
  }

  @Test
  void testVideoInputEntityEqualsAndHashCode() {
    VideoInputEntity v1 = VideoInputEntity.builder().id("1").analysisId("a").videoId("v").build();
    VideoInputEntity v2 = VideoInputEntity.builder().id("1").analysisId("a").videoId("v").build();
    assertThat(v1).isEqualTo(v2).hasSameHashCodeAs(v2);
  }
}

