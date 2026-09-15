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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class IapAssertionVerifierTest {

  private static final String ASSERTION_HEADER = "X-Goog-IAP-JWT-Assertion";

  @Test
  void googlerAssertionYieldsLdap() {
    IapAssertionVerifier verifier = verifierFor(assertionFor("jdoe@google.com"));

    Requester requester = verifier.resolve(requestWithAssertion("token"));

    assertThat(requester.kind()).isEqualTo(Requester.Kind.GOOGLER);
    assertThat(requester.ldap()).isEqualTo("jdoe");
    assertThat(requester.email()).isEqualTo("jdoe@google.com");
    assertThat(requester.attributionId()).isEqualTo("jdoe");
    assertThat(requester.isGoogler()).isTrue();
  }

  @Test
  void serviceAccountAssertionIsNotTreatedAsAGoogler() {
    IapAssertionVerifier verifier =
        verifierFor(assertionFor("bulkaibcd-runtime@proj.iam.gserviceaccount.com"));

    Requester requester = verifier.resolve(requestWithAssertion("token"));

    assertThat(requester.kind()).isEqualTo(Requester.Kind.SERVICE_ACCOUNT);
    assertThat(requester.ldap()).isNull();
    assertThat(requester.isGoogler()).isFalse();
    assertThat(requester.attributionId())
        .isEqualTo("bulkaibcd-runtime@proj.iam.gserviceaccount.com");
  }

  @Test
  void rejectedAssertionYieldsUnknownRatherThanThrowing() {
    JwtDecoder decoder = mock(JwtDecoder.class);
    when(decoder.decode(anyString())).thenThrow(new JwtException("bad signature"));
    IapAssertionVerifier verifier = new IapAssertionVerifier(decoder, "");

    Requester requester = verifier.resolve(requestWithAssertion("forged"));

    assertThat(requester).isEqualTo(Requester.unknown());
    assertThat(requester.attributionId()).isNull();
  }

  @Test
  void missingAssertionYieldsUnknownWhenNoDevFallbackConfigured() {
    IapAssertionVerifier verifier = new IapAssertionVerifier(mock(JwtDecoder.class), "");

    Requester requester = verifier.resolve(requestWithAssertion(null));

    assertThat(requester).isEqualTo(Requester.unknown());
  }

  @Test
  void missingAssertionUsesDevLdapWhenConfigured() {
    IapAssertionVerifier verifier = new IapAssertionVerifier(mock(JwtDecoder.class), "localdev");

    Requester requester = verifier.resolve(requestWithAssertion(null));

    assertThat(requester.kind()).isEqualTo(Requester.Kind.GOOGLER);
    assertThat(requester.ldap()).isEqualTo("localdev");
  }

  /**
   * The plaintext email header is forgeable by anything that can reach the container, so it must
   * never influence the resolved identity.
   */
  @Test
  void plaintextEmailHeaderIsIgnored() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(ASSERTION_HEADER)).thenReturn(null);
    when(request.getHeader("X-Goog-Authenticated-User-Email"))
        .thenReturn("accounts.google.com:ceo@google.com");
    when(request.getRequestURI()).thenReturn("/api/v2/input/submit");
    IapAssertionVerifier verifier = new IapAssertionVerifier(mock(JwtDecoder.class), "");

    Requester requester = verifier.resolve(request);

    assertThat(requester).isEqualTo(Requester.unknown());
  }

  @Test
  void assertionWithoutEmailClaimYieldsUnknown() {
    Jwt withoutEmail =
        Jwt.withTokenValue("token")
            .header("alg", "ES256")
            .claim("sub", "accounts.google.com:123")
            .issuedAt(Instant.now().minus(1, ChronoUnit.MINUTES))
            .expiresAt(Instant.now().plus(5, ChronoUnit.MINUTES))
            .build();

    Requester requester = verifierFor(withoutEmail).resolve(requestWithAssertion("token"));

    assertThat(requester).isEqualTo(Requester.unknown());
  }

  private static IapAssertionVerifier verifierFor(Jwt assertion) {
    JwtDecoder decoder = mock(JwtDecoder.class);
    when(decoder.decode(anyString())).thenReturn(assertion);
    return new IapAssertionVerifier(decoder, "");
  }

  private static Jwt assertionFor(String email) {
    return new Jwt(
        "token",
        Instant.now().minus(1, ChronoUnit.MINUTES),
        Instant.now().plus(5, ChronoUnit.MINUTES),
        Map.of("alg", "ES256"),
        Map.of(
            "iss", "https://cloud.google.com/iap",
            "sub", "accounts.google.com:1132953",
            "email", email,
            "aud", List.of("/projects/123/locations/us-central1/services/bulkaibcd")));
  }

  private static HttpServletRequest requestWithAssertion(String assertion) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(ASSERTION_HEADER)).thenReturn(assertion);
    when(request.getRequestURI()).thenReturn("/api/v2/input/submit");
    return request;
  }
}
