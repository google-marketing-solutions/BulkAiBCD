package com.bulkaibcd.service;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.Task;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class CloudTasksClientAdapter implements TaskQueueAdapter {

  private final CloudTasksClient client;

  public CloudTasksClientAdapter(CloudTasksClient client) {
    this.client = client;
  }

  @Override
  public void createTask(String queuePath, Task task) throws IOException {
    client.createTask(queuePath, task);
  }
}
