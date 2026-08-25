package com.bulkaibcd.service.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.bulkaibcd.model.GuidelineRelevance;
import com.bulkaibcd.model.VideoMetadataEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamicScoringServiceTest {

  private DynamicScoringService service;

  @BeforeEach
  void setUp() {
    service = new DynamicScoringService();
  }

  @Test
  void isGuidelineRelevantForDifferentObjectives() {
    GuidelineRelevance r =
        GuidelineRelevance.builder()
            .parameterId("p1")
            .core(1)
            .awareness(0)
            .consideration(1)
            .action(0)
            .build();

    assertThat(service.isGuidelineRelevant(r, "awareness")).isFalse();
    assertThat(service.isGuidelineRelevant(r, "consideration")).isTrue();
    assertThat(service.isGuidelineRelevant(r, "action")).isFalse();
    assertThat(service.isGuidelineRelevant(r, "core_unknown")).isTrue();
    assertThat(service.isGuidelineRelevant(r, null)).isTrue();
    assertThat(service.isGuidelineRelevant(r, "unknown_custom_obj")).isTrue();
  }

  @Test
  void calculateDimensionScore() {
    assertThat(service.calculateDimensionScore(4, 3)).isEqualTo(75);
    assertThat(service.calculateDimensionScore(3, 1)).isEqualTo(33);
    assertThat(service.calculateDimensionScore(0, 0)).isEqualTo(100);
  }

  @Test
  void calculateAverageScore() {
    assertThat(service.calculateAverageScore(null)).isNull();

    VideoMetadataEntity v1 =
        VideoMetadataEntity.builder()
            .aScore(80)
            .bScore(60)
            .cScore(70)
            .dScore(90)
            .build();
    assertThat(service.calculateAverageScore(v1)).isEqualTo(75);

    VideoMetadataEntity v2 = VideoMetadataEntity.builder().aScore(100).build();
    assertThat(service.calculateAverageScore(v2)).isEqualTo(100);

    VideoMetadataEntity vEmpty = VideoMetadataEntity.builder().build();
    assertThat(service.calculateAverageScore(vEmpty)).isNull();
  }
}
