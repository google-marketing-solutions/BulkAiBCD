/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bulkaibcd.web;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Wiring for the decoder that validates Identity-Aware Proxy assertion JWTs.
 *
 * <p>IAP signs its assertions with <b>ES256</b> and publishes the corresponding public keys as a
 * JWK set. Nimbus fetches that key set lazily, caches it, and follows key rotation via the {@code
 * kid} header, so no key material is ever checked in or refreshed by hand.
 */
@Configuration
public class IapJwtConfig {

  /** Elliptic-curve JWK set IAP signs its assertions with. */
  private static final String IAP_JWKS_URI = "https://www.gstatic.com/iap/verify/public_key-jwk";

  /** Issuer claim stamped on every IAP assertion. */
  static final String IAP_ISSUER = "https://cloud.google.com/iap";

  /**
   * Builds the decoder used to verify IAP assertions.
   *
   * <p>Signature, issuer and expiry are always enforced. The audience check binds a token to this
   * specific Cloud Run service and is therefore the difference between "a valid IAP token" and "a
   * valid IAP token <i>for us</i>" — without it, an assertion minted for any other IAP-protected
   * app in any project would be accepted. It is nonetheless gated behind {@code
   * app.iap.enforce-audience} because the audience string for Cloud Run's direct IAP integration is
   * not documented and must be observed from a live request before it can be pinned. Run permissive
   * once, read the audience out of the startup log, set {@code IAP_AUDIENCE}, then enforce.
   *
   * @param expectedAudience the audience string to require, ignored when enforcement is off
   * @param enforceAudience whether to reject assertions whose audience does not match
   * @return a decoder that validates signature, issuer, expiry and optionally audience
   */
  @Bean
  public JwtDecoder iapJwtDecoder(
      @Value("${app.iap.expected-audience:}") String expectedAudience,
      @Value("${app.iap.enforce-audience:false}") boolean enforceAudience) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(IAP_JWKS_URI)
            .jwsAlgorithm(SignatureAlgorithm.ES256)
            .build();
    decoder.setJwtValidator(buildValidator(expectedAudience, enforceAudience));
    return decoder;
  }

  private static OAuth2TokenValidator<Jwt> buildValidator(
      String expectedAudience, boolean enforceAudience) {
    JwtTimestampValidator timestamps = new JwtTimestampValidator();
    JwtIssuerValidator issuer = new JwtIssuerValidator(IAP_ISSUER);
    if (!enforceAudience || expectedAudience == null || expectedAudience.isBlank()) {
      return new DelegatingOAuth2TokenValidator<>(timestamps, issuer);
    }
    JwtClaimValidator<List<String>> audience =
        new JwtClaimValidator<>(
            JwtClaimNames.AUD, claim -> claim != null && claim.contains(expectedAudience));
    return new DelegatingOAuth2TokenValidator<>(timestamps, issuer, audience);
  }
}
