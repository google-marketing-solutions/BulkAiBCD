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

package com.bulkaibcd.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

import com.bulkaibcd.BulkAibcdApplication;
import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.SubmitAnalysisRequest;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.FirestoreEmulatorContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    classes = BulkAibcdApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Testcontainers
@TestPropertySource(
    properties = {
      "google.cloud.project.id=bulkaibcd-it",
      "google.cloud.tasks.queue=it-queue",
      "google.cloud.tasks.service-account=it-sa@test.iam.gserviceaccount.com",
      "app.backend-url=http://localhost:0"
    })
class InputControllerIT {

  @Container
  static final FirestoreEmulatorContainer firestore =
      new FirestoreEmulatorContainer(
          DockerImageName.parse("gcr.io/google.com/cloudsdktool/cloud-sdk:emulators"));

  @DynamicPropertySource
  static void firestoreProps(DynamicPropertyRegistry registry) {
    registry.add("spring.cloud.gcp.firestore.host-port", firestore::getEmulatorEndpoint);
    registry.add("spring.cloud.gcp.firestore.project-id", () -> "bulkaibcd-it");
    registry.add("spring.cloud.gcp.firestore.emulator.enabled", () -> "true");
  }

  @LocalServerPort int port;

  @Autowired AnalysisRequestRepository repo;

  @MockBean CloudTasksQueueClient cloudTasksClient;

  private RestTemplate http;
  private String baseUrl;

  @BeforeEach
  void setUp() {
    http = new RestTemplate();
    baseUrl = "http://localhost:" + port + "/api/v2/input";
    repo.deleteAll().block();
  }

  @Test
  void submitPersistsRecordAndIsRetrievable() throws Exception {
    doNothing().when(cloudTasksClient).enqueueTask(anyString(), anyString());

    SubmitAnalysisRequest body =
        SubmitAnalysisRequest.builder()
            .requesterId("it-user")
            .analysisName("IT Run")
            .analysisType("standard")
            .brandName("Acme")
            .marketingObjective("core_unknown")
            .videos(
                List.of(
                    SubmitAnalysisRequest.VideoInput.builder()
                        .sourceType("youtube")
                        .videoName("Sample YT Title")
                        .videoUrl("https://youtu.be/abc")
                        .build()))
            .build();

    ResponseEntity<String> submit =
        http.postForEntity(baseUrl + "/submit", body, String.class);
    assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.OK);
    String analysisId = submit.getBody();
    assertThat(analysisId).isNotBlank();

    // Record should be persisted with status PENDING.
    AnalysisRequestEntity fetched = repo.findById(analysisId).block();
    assertThat(fetched).isNotNull();
    if (fetched != null) {
      assertThat(fetched.getAnalysisStatus()).isEqualTo("PENDING");
      assertThat(fetched.getCreatedAt()).isNotNull();
    }

    // GET /list returns it.
    ResponseEntity<JsonNode> list =
        http.getForEntity(baseUrl + "/list/it-user", JsonNode.class);
    assertThat(list.getBody()).isNotNull();
    if (list.getBody() != null) {
      assertThat(list.getBody().isArray()).isTrue();
      boolean found = false;
      for (JsonNode row : list.getBody()) {
        if (analysisId.equals(row.get("analysisId").asText())) {
          found = true;
          break;
        }
      }
      assertThat(found).isTrue();
    }
  }

  @Test
  void listWithNonexistentUserReturnsEmptyList() {
    ResponseEntity<JsonNode> list =
        http.getForEntity(baseUrl + "/list/nonexistent-user", JsonNode.class);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getBody()).isNotNull();
    if (list.getBody() != null) {
      assertThat(list.getBody().isArray()).isTrue();
      assertThat(list.getBody().size()).isEqualTo(0);
    }
  }

  @Test
  void getAnalysisForNonexistentIdReturnsNotFound() {
    assertThatThrownBy(() -> http.getForEntity(baseUrl + "/invalid-id", String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
  }

  @Test
  void cancelAnalysisForNonexistentIdReturnsNotFound() {
    assertThatThrownBy(() -> http.postForEntity(baseUrl + "/invalid-id/cancel", null, String.class))
        .isInstanceOf(HttpClientErrorException.NotFound.class);
  }
}
