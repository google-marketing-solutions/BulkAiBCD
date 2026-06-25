package com.bulkaibcd.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoogleAdsStatusDto {
  private boolean configured;
  private boolean authorized;
}
