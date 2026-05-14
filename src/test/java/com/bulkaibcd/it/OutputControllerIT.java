package com.bulkaibcd.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.bulkaibcd.BulkAibcdApplication;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.CloudTasksService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
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
class OutputControllerIT {

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

  @Autowired VideoMetadataRepository repo;

  @MockBean CloudTasksService cloudTasksService;

  @Test
  void getVideosReturnsPersistedMetadata() {
    repo.deleteAll().block();
    VideoMetadataEntity v =
        VideoMetadataEntity.builder()
            .id("it-1")
            .analysisId("ana-it")
            .videoId("v-1")
            .status("COMPLETED")
            .brand("TestBrand")
            .build();
    repo.save(v).block();

    RestTemplate http = new RestTemplate();
    ResponseEntity<VideoMetadataEntity[]> response =
        http.getForEntity(
            "http://localhost:" + port + "/api/v2/output/videos/ana-it",
            VideoMetadataEntity[].class);

    assertThat(response.getBody()).hasSize(1);
    assertThat(response.getBody()[0].getVideoId()).isEqualTo("v-1");
    assertThat(response.getBody()[0].getBrand()).isEqualTo("TestBrand");
  }

  @Test
  void csvReportContainsPersistedRow() {
    repo.deleteAll().block();
    repo.save(
            VideoMetadataEntity.builder()
                .id("it-2")
                .analysisId("ana-csv")
                .videoId("v-csv")
                .status("COMPLETED")
                .brand("Acme")
                .build())
        .block();

    RestTemplate http = new RestTemplate();
    ResponseEntity<byte[]> response =
        http.getForEntity(
            "http://localhost:" + port + "/api/v2/output/report/ana-csv", byte[].class);

    String csv = new String(response.getBody(), StandardCharsets.UTF_8);
    assertThat(csv).startsWith("VideoID,Brand,Product,Language,Vertical,AssetName\n");
    assertThat(csv).contains("\"v-csv\"");
    assertThat(csv).contains("\"Acme\"");
  }
}
