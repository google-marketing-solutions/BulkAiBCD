package com.bulkaibcd.service.ads;

import com.bulkaibcd.model.GoogleAdsConfigDto;
import com.bulkaibcd.model.GoogleAdsStatusDto;
import reactor.core.publisher.Mono;

public interface GoogleAdsConfigService {
  Mono<GoogleAdsStatusDto> getStatus();
  Mono<String> configureAndGetAuthUrl(GoogleAdsConfigDto request, String redirectUri);
}
