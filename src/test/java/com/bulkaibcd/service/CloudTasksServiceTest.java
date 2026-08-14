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
