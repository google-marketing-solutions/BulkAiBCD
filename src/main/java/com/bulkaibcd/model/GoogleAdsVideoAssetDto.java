package com.bulkaibcd.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoogleAdsVideoAssetDto {
  private String id;
  private String name;
  private String youtubeVideoId;
}
