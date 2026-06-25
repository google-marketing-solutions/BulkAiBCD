package com.bulkaibcd.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoogleAdsConfigDto {
  private String clientId;
  private String clientSecret;
  private String developerToken;
  private String frontendOrigin;
}
