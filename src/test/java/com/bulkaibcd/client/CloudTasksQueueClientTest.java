package com.bulkaibcd.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.tasks.v2.Task;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class CloudTasksQueueClientTest {

  private TaskQueueAdapter adapter;
  private CloudTasksQueueClient client;

  @BeforeEach
  void setUp() {
    adapter = mock(TaskQueueAdapter.class);
    client = new CloudTasksQueueClient(adapter);
    ReflectionTestUtils.setField(client, "projectId", "test-project");
    ReflectionTestUtils.setField(client, "location", "us-central1");
    ReflectionTestUtils.setField(client, "queueId", "test-queue");
    ReflectionTestUtils.setField(client, "serviceAccountEmail", "sa@test-project.iam.gserviceaccount.com");
    ReflectionTestUtils.setField(client, "backendUrl", "https://backend.example.com");
  }

  @Test
  void enqueueTaskBuildsExpectedTaskWithOidcAndCallsAdapter() throws Exception {
    doNothing().when(adapter).createTask(anyString(), any(Task.class));

    client.enqueueTask("/api/v2/worker/prepare", "{\"analysisId\":\"123\"}");

    String expectedQueuePath = "projects/test-project/locations/us-central1/queues/test-queue";
    ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
    verify(adapter).createTask(eq(expectedQueuePath), taskCaptor.capture());

    Task captured = taskCaptor.getValue();
    assertThat(captured.getHttpRequest().getUrl()).isEqualTo("https://backend.example.com/api/v2/worker/prepare");
    assertThat(captured.getHttpRequest().getOidcToken().getServiceAccountEmail())
        .isEqualTo("sa@test-project.iam.gserviceaccount.com");
    assertThat(captured.getHttpRequest().getBody().toStringUtf8()).isEqualTo("{\"analysisId\":\"123\"}");
  }

  @Test
  void enqueueTaskWithSuffixAndDelaySetsTaskNameAndScheduleTime() throws Exception {
    doNothing().when(adapter).createTask(anyString(), any(Task.class));

    client.enqueueTask("/api/v2/worker/check", "{}", "my-suffix-1", 60);

    ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
    verify(adapter).createTask(anyString(), taskCaptor.capture());

    Task captured = taskCaptor.getValue();
    assertThat(captured.getName()).isEqualTo("projects/test-project/locations/us-central1/queues/test-queue/tasks/my-suffix-1");
    assertThat(captured.hasScheduleTime()).isTrue();
  }

  @Test
  void enqueueTaskHandlesAlreadyExistsExceptionGracefully() throws Exception {
    AlreadyExistsException ex = mock(AlreadyExistsException.class);
    doThrow(ex).when(adapter).createTask(anyString(), any(Task.class));

    // Should not throw
    client.enqueueTask("/api/v2/worker/check", "{}", "duplicate-task", 30);
  }

  @Test
  void enqueueTaskRethrowsIOExceptionWhenAdapterFails() throws Exception {
    doThrow(new IOException("Cloud Tasks quota exceeded"))
        .when(adapter)
        .createTask(anyString(), any(Task.class));

    assertThatThrownBy(() -> client.enqueueTask("/api/test", "{}"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Cloud Tasks quota exceeded");
  }
}

