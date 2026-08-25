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

package com.bulkaibcd.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.GcsClient;
import com.bulkaibcd.model.GenerateDeckRequest;
import com.bulkaibcd.model.GenerateReportRequest;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.output.GenerateDeckService;
import com.bulkaibcd.service.output.GenerateSpreadsheetReportService;
import com.google.cloud.storage.BlobInfo;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OutputControllerTest {

  private VideoMetadataRepository repo;
  private GenerateDeckService deckService;
  private GenerateSpreadsheetReportService reportService;
  private GcsClient gcsClient;
  private OutputController controller;

  @BeforeEach
  void setUp() {
    repo = mock(VideoMetadataRepository.class);
    deckService = mock(GenerateDeckService.class);
    reportService = mock(GenerateSpreadsheetReportService.class);
    gcsClient = mock(GcsClient.class);
    controller = new OutputController(repo, deckService, reportService, gcsClient);
  }

  @Test
  void getVideosStripsRawVisualFieldsAndSignsGcsUrl() throws Exception {
    VideoMetadataEntity v1 =
        VideoMetadataEntity.builder()
            .id("1")
            .videoId("a")
            .shot("raw-shot")
            .text("raw-text")
            .speech("raw-speech")
            .logo("raw-logo")
            .objects("raw-objects")
            .face("raw-face")
            .person("raw-person")
            .labelName("raw-label")
            .explicit("raw-explicit")
            .gcsObjectId("my-bucket/path/video.mp4")
            .build();

    when(repo.findByAnalysisId("ana-1")).thenReturn(Flux.just(v1));
    URL signed = new URL("https://storage.googleapis.com/my-bucket/path/video.mp4?signed");
    when(gcsClient.signUrl(any(BlobInfo.class), eq(60L), eq(TimeUnit.MINUTES), any(), any()))
        .thenReturn(signed);

    StepVerifier.create(controller.getVideos("ana-1"))
        .assertNext(
            v -> {
              assertThat(v.getVideoId()).isEqualTo("a");
              assertThat(v.getShot()).isNull();
              assertThat(v.getText()).isNull();
              assertThat(v.getSpeech()).isNull();
              assertThat(v.getLogo()).isNull();
              assertThat(v.getObjects()).isNull();
              assertThat(v.getFace()).isNull();
              assertThat(v.getPerson()).isNull();
              assertThat(v.getLabelName()).isNull();
              assertThat(v.getExplicit()).isNull();
              assertThat(v.getSignedUrl()).isEqualTo(signed.toString());
            })
        .verifyComplete();
  }

  @Test
  void generateDeckCallsGenerateDeckService() {
    Map<String, Object> payload = Map.of("videoIds", List.of("v1", "v2"));
    ResponseEntity<Map<String, Object>> response = ResponseEntity.ok(Map.of("presentationUrl", "https://slides.url"));
    when(deckService.execute(any(GenerateDeckRequest.class))).thenReturn(Mono.just(response));

    StepVerifier.create(controller.generateDeck("ana-1", "user-tok", "leg-tok", payload))
        .expectNext(response)
        .verifyComplete();
  }

  @Test
  void generateSpreadsheetReportCallsGenerateSpreadsheetReportService() {
    ResponseEntity<Map<String, String>> response = ResponseEntity.ok(Map.of("sheetUrl", "https://sheets.url"));
    when(reportService.execute(any(GenerateReportRequest.class))).thenReturn(Mono.just(response));

    StepVerifier.create(controller.generateSpreadsheetReport("ana-1", "user-tok", "leg-tok"))
        .expectNext(response)
        .verifyComplete();
  }
}

