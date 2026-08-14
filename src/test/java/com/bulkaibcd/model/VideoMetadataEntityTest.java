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
    assertThat(entity.getBrand()).isEqualTo("Acme");
    assertThat(entity.getExplicit()).isEqualTo("false");
  }
}
