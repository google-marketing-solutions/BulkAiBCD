package com.bulkaibcd.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureParameter {
  private String id;
  private String name;
  private String category;
  private String criteria;
  private String promptTemplate;
  /** "LIGHT" | "STANDARD" */
  private String type;
}
