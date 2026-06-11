package com.bulkaibcd.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateDeckRequest {
  private String analysisId;
  private String userAccessToken;
  private String legacyAccessToken;
  private List<String> videoIds;
}
