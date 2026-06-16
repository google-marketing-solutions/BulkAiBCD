package com.bulkaibcd.model;

import lombok.Data;

@Data
public class ProcessRequest {
  private String analysisId;
  private String gcsUri;
}
