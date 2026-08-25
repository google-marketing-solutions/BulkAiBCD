package com.bulkaibcd.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GeminiClientTest {

  private GeminiClient client;

  @BeforeEach
  void setUp() {
    client = new GeminiClient();
    ReflectionTestUtils.setField(client, "projectId", "test-project");
    ReflectionTestUtils.setField(client, "location", "us-central1");
    ReflectionTestUtils.setField(client, "modelName", "gemini-1.5-flash");
  }

  @Test
  void testPropertiesInjection() {
    assertThat(ReflectionTestUtils.getField(client, "projectId")).isEqualTo("test-project");
    assertThat(ReflectionTestUtils.getField(client, "location")).isEqualTo("us-central1");
    assertThat(ReflectionTestUtils.getField(client, "modelName")).isEqualTo("gemini-1.5-flash");
  }
}

