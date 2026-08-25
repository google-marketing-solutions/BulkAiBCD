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

package com.bulkaibcd.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class VideoMetadataEntityTest {

  @Test
  void builderPopulatesAllFields() {
    NotDetectedFeatureEntity ndf = new NotDetectedFeatureEntity("Logo", "No logo detected");
    VideoMetadataEntity entity =
        VideoMetadataEntity.builder()
            .id("doc-1")
            .analysisId("ana-1")
            .videoId("vid-1")
            .videoName("My Video")
            .videoUrl("https://youtube.com/watch?v=123")
            .gcsObjectId("uploads/vid-1.mp4")
            .thumbnailUrl("data:image/jpeg;base64,...")
            .sourceType("YOUTUBE")
            .format("LONG")
            .status("COMPLETED")
            .errorMessage("None")
            .aScore(85)
            .bScore(90)
            .cScore(75)
            .dScore(60)
            .relevantFeatures(List.of("f1", "f2"))
            .features(List.of("f1"))
            .notDetected(List.of("f2"))
            .notDetectedFeatures(List.of(ndf))
            .recommendations("Add clear call to action")
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
            .signedUrl("https://storage.googleapis.com/signed-url")
            .build();

    assertThat(entity.getId()).isEqualTo("doc-1");
    assertThat(entity.getAnalysisId()).isEqualTo("ana-1");
    assertThat(entity.getVideoId()).isEqualTo("vid-1");
    assertThat(entity.getVideoName()).isEqualTo("My Video");
    assertThat(entity.getVideoUrl()).isEqualTo("https://youtube.com/watch?v=123");
    assertThat(entity.getGcsObjectId()).isEqualTo("uploads/vid-1.mp4");
    assertThat(entity.getThumbnailUrl()).isEqualTo("data:image/jpeg;base64,...");
    assertThat(entity.getSourceType()).isEqualTo("YOUTUBE");
    assertThat(entity.getFormat()).isEqualTo("LONG");
    assertThat(entity.getStatus()).isEqualTo("COMPLETED");
    assertThat(entity.getErrorMessage()).isEqualTo("None");
    assertThat(entity.getAScore()).isEqualTo(85);
    assertThat(entity.getBScore()).isEqualTo(90);
    assertThat(entity.getCScore()).isEqualTo(75);
    assertThat(entity.getDScore()).isEqualTo(60);
    assertThat(entity.getRelevantFeatures()).containsExactly("f1", "f2");
    assertThat(entity.getFeatures()).containsExactly("f1");
    assertThat(entity.getNotDetected()).containsExactly("f2");
    assertThat(entity.getNotDetectedFeatures()).containsExactly(ndf);
    assertThat(entity.getRecommendations()).isEqualTo("Add clear call to action");
    assertThat(entity.getBrand()).isEqualTo("Acme");
    assertThat(entity.getProduct()).isEqualTo("Widget");
    assertThat(entity.getVideoLanguage()).isEqualTo("en-US");
    assertThat(entity.getVertical()).isEqualTo("Retail");
    assertThat(entity.getAssetName()).isEqualTo("Q3-Spot");
    assertThat(entity.getShot()).isEqualTo("establishing");
    assertThat(entity.getText()).isEqualTo("Buy now!");
    assertThat(entity.getSpeech()).isEqualTo("Hello");
    assertThat(entity.getLogo()).isEqualTo("acme-logo");
    assertThat(entity.getObjects()).isEqualTo("car,sign");
    assertThat(entity.getFace()).isEqualTo("1");
    assertThat(entity.getPerson()).isEqualTo("driver");
    assertThat(entity.getLabelName()).isEqualTo("automotive");
    assertThat(entity.getExplicit()).isEqualTo("false");
    assertThat(entity.getSignedUrl()).isEqualTo("https://storage.googleapis.com/signed-url");
  }

  @Test
  void testEqualsAndHashCode() {
    VideoMetadataEntity v1 = VideoMetadataEntity.builder().id("1").analysisId("a").build();
    VideoMetadataEntity v2 = VideoMetadataEntity.builder().id("1").analysisId("a").build();
    assertThat(v1).isEqualTo(v2).hasSameHashCodeAs(v2);
  }
}
