package com.bulkaibcd.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VideoMetadataEntityTest {

  @Test
  void builderPopulatesAllFields() {
    VideoMetadataEntity entity =
        VideoMetadataEntity.builder()
            .id("doc-1")
            .analysisId("ana-1")
            .videoId("vid-1")
            .status("COMPLETED")
            .attempts(2)
            .errorMessage(null)
            .brand("Acme")
            .product("Widget")
            .videoLanguage("en-US")
            .vertical("Retail")
            .assetName("Q3-Spot")
            .shot("establishing")
            .text("Buy now!")
            .speech("Hello")
            .logo("acme-logo")
            .objects("car,sign")
            .face("1")
            .person("driver")
            .labelName("automotive")
            .explicit("false")
            .build();

    assertThat(entity.getId()).isEqualTo("doc-1");
    assertThat(entity.getAnalysisId()).isEqualTo("ana-1");
    assertThat(entity.getVideoId()).isEqualTo("vid-1");
    assertThat(entity.getStatus()).isEqualTo("COMPLETED");
    assertThat(entity.getAttempts()).isEqualTo(2);
    assertThat(entity.getBrand()).isEqualTo("Acme");
    assertThat(entity.getExplicit()).isEqualTo("false");
  }

  @Test
  void defaultAttemptsIsZero() {
    VideoMetadataEntity entity = new VideoMetadataEntity();
    assertThat(entity.getAttempts()).isZero();
  }
}
