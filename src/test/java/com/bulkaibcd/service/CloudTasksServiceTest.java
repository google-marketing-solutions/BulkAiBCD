package com.bulkaibcd.service;

import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.Task;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

class CloudTasksServiceTest {

  private CloudTasksService newConfiguredService() {
    CloudTasksService s = new CloudTasksService();
    ReflectionTestUtils.setField(s, "projectId", "p");
    ReflectionTestUtils.setField(s, "location", "us-central1");
    ReflectionTestUtils.setField(s, "queueId", "q");
    ReflectionTestUtils.setField(s, "serviceAccountEmail", "sa@p.iam.gserviceaccount.com");
    ReflectionTestUtils.setField(s, "backendUrl", "https://backend.example.com");
    return s;
  }

  @Test
  void enqueueTaskPropagatesClientCreationIOException() {
    CloudTasksService service = newConfiguredService();

    try (MockedStatic<CloudTasksClient> staticMock = mockStatic(CloudTasksClient.class)) {
      staticMock
          .when(CloudTasksClient::create)
          .thenThrow(new IOException("ADC missing"));

      assertThatIOException()
          .isThrownBy(() -> service.enqueueTask("/api/v2/worker/prepare", "{\"a\":1}"))
          .withMessageContaining("ADC missing");
    }
  }

  @Test
  void enqueueTaskCallsCreateTaskWithExpectedQueuePath() throws Exception {
    CloudTasksService service = newConfiguredService();
    CloudTasksClient fakeClient = mock(CloudTasksClient.class);
    when(fakeClient.createTask(anyString(), any(Task.class)))
        .thenReturn(Task.newBuilder().build());

    try (MockedStatic<CloudTasksClient> staticMock = mockStatic(CloudTasksClient.class)) {
      staticMock.when(CloudTasksClient::create).thenReturn(fakeClient);

      service.enqueueTask("/api/v2/worker/prepare", "{\"a\":1}");

      String expectedQueuePath = "projects/p/locations/us-central1/queues/q";
      verify(fakeClient).createTask(org.mockito.ArgumentMatchers.eq(expectedQueuePath), any(Task.class));
    }
  }

  @Test
  void enqueueTaskWithNullPayloadStillCallsCreateTask() throws Exception {
    CloudTasksService service = newConfiguredService();
    CloudTasksClient fakeClient = mock(CloudTasksClient.class);
    when(fakeClient.createTask(anyString(), any(Task.class)))
        .thenReturn(Task.newBuilder().build());

    try (MockedStatic<CloudTasksClient> staticMock = mockStatic(CloudTasksClient.class)) {
      staticMock.when(CloudTasksClient::create).thenReturn(fakeClient);

      service.enqueueTask("/api/v2/worker/prepare", null);

      verify(fakeClient).createTask(anyString(), any(Task.class));
    }
  }
}
