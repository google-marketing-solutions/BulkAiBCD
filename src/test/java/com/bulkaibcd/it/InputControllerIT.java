package com.bulkaibcd.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

import com.google.common.truth.Expect;
import org.junit.Rule;

import com.bulkaibcd.BulkAibcdApplication;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.SubmitAnalysisRequest;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.service.CloudTasksService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
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
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.FirestoreEmulatorContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = BulkAibcdApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

  @Rule public final Expect expect = Expect.create();

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

  @MockBean CloudTasksService cloudTasksService;

  private RestTemplate http;
  private String baseUrl;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    http = new RestTemplate();
    baseUrl = "http://localhost:" + port + "/api/v2/input";
    repo.deleteAll().block();
  }

  @Test
  void submitPersistsRecordAndIsRetrievable() throws Exception {
    doNothing().when(cloudTasksService).enqueueTask(anyString(), anyString());

    SubmitAnalysisRequest body = SubmitAnalysisRequest.builder()
        .requesterId("it-user")
        .analysisName("IT Run")
        .analysisType("standard")
        .brandName("Acme")
        .marketingObjective("core_unknown")
        .videos(List.of(
            SubmitAnalysisRequest.VideoInput.builder()
                .sourceType("youtube")
                .videoName("Sample YT Title")
                .videoUrl("https://youtu.be/abc")
                .build()))
        .build();

    ResponseEntity<String> submit =
        http.postForEntity(baseUrl + "/submit", body, String.class);
    expect.that(submit.getStatusCode()).isEqualTo(HttpStatus.OK);
    String analysisId = submit.getBody();
    expect.that(analysisId).isNotBlank();

    // Record should be persisted with status PENDING.
    AnalysisRequestEntity fetched = repo.findById(analysisId).block();
    expect.that(fetched).isNotNull();
    if (fetched != null) {
      expect.that(fetched.getAnalysisStatus()).isEqualTo("PENDING");
      expect.that(fetched.getCreatedAt()).isNotNull();
    }

    // GET /list returns it. Use JsonNode to dodge com.google.cloud.Timestamp Jackson binding
    // (the field serializes fine on the wire but has no default Jackson deserializer).
    ResponseEntity<JsonNode> list =
        http.getForEntity(baseUrl + "/list/it-user", JsonNode.class);
    expect.that(list.getBody()).isNotNull();
    if (list.getBody() != null) {
      expect.that(list.getBody().isArray()).isTrue();
      boolean found = false;
      for (JsonNode row : list.getBody()) {
        if (analysisId.equals(row.get("analysisId").asText())) {
          found = true;
          break;
        }
      }
      expect.that(found).isTrue();
    }
  }

  @Test
  void listWithNonexistentUserReturnsEmptyList() {
    ResponseEntity<JsonNode> list =
        http.getForEntity(baseUrl + "/list/nonexistent-user", JsonNode.class);
    expect.that(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    expect.that(list.getBody()).isNotNull();
    if (list.getBody() != null) {
      expect.that(list.getBody().isArray()).isTrue();
      expect.that(list.getBody().size()).isEqualTo(0);
    }
  }

  @Test
  void getAnalysisForNonexistentIdReturnsNotFound() {
    try {
      http.getForEntity(baseUrl + "/invalid-id", String.class);
      expect.withMessage("Expected HttpClientErrorException to be thrown").that(false).isTrue();
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      expect.that(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  @Test
  void cancelAnalysisForNonexistentIdReturnsNotFound() {
    try {
      http.postForEntity(baseUrl + "/invalid-id/cancel", null, String.class);
      expect.withMessage("Expected HttpClientErrorException to be thrown").that(false).isTrue();
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      expect.that(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }
}
