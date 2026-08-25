package com.bulkaibcd.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConfigControllerTest {

  @Test
  void getReturnsConfigMap() {
    ConfigController controller = new ConfigController();
    ReflectionTestUtils.setField(controller, "driveIngestServiceAccount", "sa@project.iam.gserviceaccount.com");
    ReflectionTestUtils.setField(controller, "projectId", "my-project");

    Map<String, String> config = controller.get();
    assertThat(config).containsEntry("driveIngestServiceAccount", "sa@project.iam.gserviceaccount.com");
    assertThat(config).containsEntry("projectId", "my-project");
  }

  @Test
  void getWithNullsReturnsEmptyStrings() {
    ConfigController controller = new ConfigController();
    ReflectionTestUtils.setField(controller, "driveIngestServiceAccount", null);
    ReflectionTestUtils.setField(controller, "projectId", null);

    Map<String, String> config = controller.get();
    assertThat(config).containsEntry("driveIngestServiceAccount", "");
    assertThat(config).containsEntry("projectId", "");
  }
}
