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

package com.bulkaibcd;

import static org.assertj.core.api.Assertions.assertThat;

import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.GoogleAdsCredentialsRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.youtube.YouTubeResolveService;
import com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = BulkAibcdApplication.class)
@ActiveProfiles("dev")
@org.springframework.context.annotation.Import(SpringContextBootTest.FirestoreChannelSimulationConfig.class)
@TestPropertySource(
    properties = {
      "spring.cloud.gcp.firestore.enabled=false",
      "spring.cloud.gcp.firestore.emulator.enabled=false",
      "google.cloud.project.id=bulkaibcd-local",
      "google.cloud.tasks.queue=bulkaibcd-queue",
      "google.cloud.tasks.service-account=dev@test.iam.gserviceaccount.com",
      "app.backend-url=http://localhost:8080",
    })
class SpringContextBootTest {

  @Autowired private ApplicationContext applicationContext;

  @MockBean private AnalysisRequestRepository analysisRequestRepository;
  @MockBean private VideoMetadataRepository videoMetadataRepository;
  @MockBean private GoogleAdsCredentialsRepository googleAdsCredentialsRepository;
  @MockBean private com.bulkaibcd.repository.VideoInputRepository videoInputRepository;
  @MockBean private CloudTasksQueueClient cloudTasksQueueClient;
  @MockBean private com.google.cloud.tasks.v2.CloudTasksClient cloudTasksClient;
  @MockBean private com.google.cloud.storage.Storage storage;
  @MockBean private com.google.cloud.firestore.Firestore firestore;
  @MockBean private com.google.cloud.spring.data.firestore.FirestoreTemplate firestoreTemplate;
  @MockBean private com.google.api.services.drive.Drive driveService;
  @MockBean private com.google.api.services.slides.v1.Slides slidesService;
  @MockBean private com.google.api.services.sheets.v4.Sheets sheetsService;

  @org.springframework.boot.test.context.TestConfiguration
  static class FirestoreChannelSimulationConfig {
    @org.springframework.context.annotation.Bean("firestoreManagedChannel")
    public io.grpc.ManagedChannel firestoreManagedChannel() {
      return io.grpc.ManagedChannelBuilder.forTarget("firestore.googleapis.com:443")
          .usePlaintext()
          .build();
    }
  }

  @Test
  void contextLoadsAndAllBeansInstantiate() {
    assertThat(applicationContext).isNotNull();
    assertThat(applicationContext.getBean(YouTubeResolveService.class)).isNotNull();
    assertThat(applicationContext.getBean(com.bulkaibcd.service.analysis.PrepareAnalysisService.class)).isNotNull();
    assertThat(applicationContext.getBean(com.bulkaibcd.controller.InputController.class)).isNotNull();
    assertThat(applicationContext.getBean(com.bulkaibcd.controller.OutputController.class)).isNotNull();
    assertThat(applicationContext.getBean(com.bulkaibcd.controller.UploadUrlController.class)).isNotNull();
    assertThat(applicationContext.getBean(com.bulkaibcd.controller.GoogleAdsController.class)).isNotNull();
    assertThat(applicationContext.getBean(com.bulkaibcd.controller.AnalysisWorkerController.class)).isNotNull();
    assertThat(applicationContext.getBean(com.bulkaibcd.controller.ConfigController.class)).isNotNull();
  }
}
