package com.bulkaibcd.client;

import com.google.cloud.tasks.v2.Task;
import java.io.IOException;

public interface TaskQueueAdapter {
  void createTask(String queuePath, Task task) throws IOException;
}
