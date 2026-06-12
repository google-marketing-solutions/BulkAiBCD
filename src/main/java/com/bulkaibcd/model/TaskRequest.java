package com.bulkaibcd.model;

import lombok.Data;

@Data
public class TaskRequest {
  private Integer executionCount;
  private String analysisId;
  private String videoId;
  private String videoUri;
  private String promptType;
}
