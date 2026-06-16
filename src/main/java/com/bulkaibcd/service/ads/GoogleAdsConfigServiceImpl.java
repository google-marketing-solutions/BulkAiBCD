package com.bulkaibcd.service.googleads;

import com.bulkaibcd.model.GoogleAdsConfigDto;
import com.bulkaibcd.model.GoogleAdsStatusDto;
import com.bulkaibcd.model.GoogleAdsCredentialsEntity;
import com.bulkaibcd.repository.GoogleAdsCredentialsRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@Slf4j
@RequiredArgsConstructor
public class GoogleAdsConfigServiceImpl implements GoogleAdsConfigService {

  private final GoogleAdsCredentialsRepository credentialsRepository;

  @Override
  public Mono<GoogleAdsStatusDto> getStatus() {
    return credentialsRepository.findById("global")
        .map(creds -> GoogleAdsStatusDto.builder()
            .configured(creds.hasValidCredentials())
            .authorized(creds.hasTokens())
            .build())
        .defaultIfEmpty(GoogleAdsStatusDto.builder()
            .configured(false)
            .authorized(false)
            .build());
  }

  @Override
  public Mono<String> configureAndGetAuthUrl(GoogleAdsConfigDto request, String redirectUri) {
    GoogleAdsCredentialsEntity entity = GoogleAdsCredentialsEntity.builder()
        .id("global")
        .clientId(request.getClientId().trim())
        .clientSecret(request.getClientSecret().trim())
        .developerToken(request.getDeveloperToken().trim())
        .build();

    return credentialsRepository.save(entity)
        .map(saved -> "https://accounts.google.com/o/oauth2/v2/auth?"
            + "client_id=" + URLEncoder.encode(saved.getClientId(), StandardCharsets.UTF_8)
            + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
            + "&response_type=code"
            + "&scope=" + URLEncoder.encode("https://www.googleapis.com/auth/adwords https://www.googleapis.com/auth/cloud-platform", StandardCharsets.UTF_8)
            + "&access_type=offline"
            + "&prompt=consent"
            + "&state=" + URLEncoder.encode(request.getFrontendOrigin() != null ? request.getFrontendOrigin() : "", StandardCharsets.UTF_8)
        );
  }
}
