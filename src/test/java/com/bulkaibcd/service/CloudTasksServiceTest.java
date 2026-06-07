package com.bulkaibcd.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doNothing;

import com.google.cloud.tasks.v2.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CloudTasksServiceTest {

  private CloudTasksService newConfiguredService(TaskQueueAdapter fakeAdapter) {
    CloudTasksService s = new CloudTasksService(fakeAdapter);
    ReflectionTestUtils.setField(s, "projectId", "p");
    ReflectionTestUtils.setField(s, "location", "us-central1");
    ReflectionTestUtils.setField(s, "queueId", "q");
    ReflectionTestUtils.setField(s, "serviceAccountEmail", "sa@p.iam.gserviceaccount.com");
    ReflectionTestUtils.setField(s, "backendUrl", "https://backend.example.com");
    return s;
  }

  @Test
  void enqueueTaskCallsCreateTaskWithExpectedQueuePath() throws Exception {
    TaskQueueAdapter fakeAdapter = mock(TaskQueueAdapter.class);
    doNothing().when(fakeAdapter).createTask(anyString(), any(Task.class));

    CloudTasksService service = newConfiguredService(fakeAdapter);

    service.enqueueTask("/api/v2/worker/prepare", "{\"a\":1}");

    String expectedQueuePath = "projects/p/locations/us-central1/queues/q";
    verify(fakeAdapter).createTask(org.mockito.ArgumentMatchers.eq(expectedQueuePath), any(Task.class));
  }

  @Test
  void enqueueTaskWithNullPayloadStillCallsCreateTask() throws Exception {
    TaskQueueAdapter fakeAdapter = mock(TaskQueueAdapter.class);
    doNothing().when(fakeAdapter).createTask(anyString(), any(Task.class));

    CloudTasksService service = newConfiguredService(fakeAdapter);

    service.enqueueTask("/api/v2/worker/prepare", null);

    verify(fakeAdapter).createTask(anyString(), any(Task.class));
  }
}
