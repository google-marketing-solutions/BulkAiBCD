package com.bulkaibcd.service.ads;

import com.bulkaibcd.model.GoogleAdsCredentialsEntity;
import reactor.core.publisher.Mono;

public interface GoogleAdsTokenService {
  Mono<String> getValidAccessToken();
  Mono<GoogleAdsCredentialsEntity> exchangeCodeForTokens(String code, String redirectUri);
}
