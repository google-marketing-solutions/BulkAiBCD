package com.bulkaibcd.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuidelineRelevance {
  @JsonProperty("parameter_id")
  private String parameterId;
  private Integer core;
  private Integer awareness;
  private Integer consideration;
  private Integer action;
}
