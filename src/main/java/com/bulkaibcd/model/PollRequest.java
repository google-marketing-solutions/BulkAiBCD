package com.bulkaibcd.model;

import lombok.Data;

@Data
public class PollRequest {
  private String analysisId;
  private String batchJobId;
  private int attemptCount;
}
