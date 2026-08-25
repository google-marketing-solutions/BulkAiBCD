package com.bulkaibcd.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class VertexBatchClientTest {

  private VertexBatchClient client;

  @BeforeEach
  void setUp() {
    client = new VertexBatchClient();
    ReflectionTestUtils.setField(client, "projectId", "test-project");
    ReflectionTestUtils.setField(client, "location", "us-central1");
  }

  @Test
  void testPropertiesInjection() {
    assertThat(ReflectionTestUtils.getField(client, "projectId")).isEqualTo("test-project");
    assertThat(ReflectionTestUtils.getField(client, "location")).isEqualTo("us-central1");
  }
}
