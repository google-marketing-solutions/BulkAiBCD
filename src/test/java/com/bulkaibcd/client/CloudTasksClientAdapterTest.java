package com.bulkaibcd.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CloudTasksClientAdapterTest {

  @Test
  void testCloudTasksClientAdapterImplementsInterface() {
    assertThat(TaskQueueAdapter.class.isAssignableFrom(CloudTasksClientAdapter.class)).isTrue();
  }
}
