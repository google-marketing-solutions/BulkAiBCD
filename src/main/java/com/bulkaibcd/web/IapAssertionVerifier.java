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

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Turns the headers Identity-Aware Proxy stamps on a request into a verified {@link Requester}.
 *
 * <p>IAP sends the caller's address twice: once as the plaintext {@code
 * X-Goog-Authenticated-User-Email} header and once inside the cryptographically signed {@code
 * X-Goog-IAP-JWT-Assertion}. Only the signed form is read here. The plaintext header is trivially
 * forgeable by anything that can reach the container — including this service's own Cloud Tasks
 * callbacks, which hold {@code roles/run.invoker} — so an audit trail built on it would not be
 * worth keeping.
 */
@Component
@Slf4j
public class IapAssertionVerifier {

  private static final String ASSERTION_HEADER = "X-Goog-IAP-JWT-Assertion";
  private static final String GOOGLER_DOMAIN = "@google.com";

  private final JwtDecoder iapJwtDecoder;
  private final String devLdap;
  private final AtomicBoolean claimsLogged = new AtomicBoolean();

  public IapAssertionVerifier(
      JwtDecoder iapJwtDecoder, @Value("${app.identity.dev-ldap:}") String devLdap) {
    this.iapJwtDecoder = iapJwtDecoder;
    this.devLdap = devLdap;
  }

  /**
   * Resolves the caller behind the given request.
   *
   * <p>Never throws: a missing, malformed, expired or wrongly-signed assertion all collapse to
   * {@link Requester#unknown()}. Callers decide what an unattributable request is allowed to do,
   * which keeps this class free of policy.
   *
   * @param request the inbound request whose IAP headers should be inspected
   * @return the verified caller, or {@link Requester#unknown()} when no identity could be proven
   */
  public Requester resolve(HttpServletRequest request) {
    String assertion = request.getHeader(ASSERTION_HEADER);
    if (assertion == null || assertion.isBlank()) {
      return resolveWithoutAssertion();
    }
    try {
      return toRequester(iapJwtDecoder.decode(assertion));
    } catch (JwtException e) {
      log.warn("Rejected IAP assertion on {}: {}", request.getRequestURI(), e.getMessage());
      return Requester.unknown();
    }
  }

  /**
   * Falls back to the configured developer LDAP when running outside an IAP-fronted deployment.
   *
   * <p>{@code app.identity.dev-ldap} is only ever set in the {@code dev} profile, so a production
   * container that somehow loses its IAP headers fails closed rather than silently inventing an
   * identity.
   */
  private Requester resolveWithoutAssertion() {
    if (devLdap == null || devLdap.isBlank()) {
      return Requester.unknown();
    }
    return new Requester(
        Requester.Kind.GOOGLER, devLdap, devLdap + GOOGLER_DOMAIN, "dev:" + devLdap);
  }

  private Requester toRequester(Jwt assertion) {
    logClaimsOnce(assertion);
    String email = assertion.getClaimAsString("email");
    String subjectId = assertion.getSubject();
    if (email == null || email.isBlank()) {
      return Requester.unknown();
    }
    if (email.endsWith(GOOGLER_DOMAIN)) {
      String ldap = email.substring(0, email.length() - GOOGLER_DOMAIN.length());
      return new Requester(Requester.Kind.GOOGLER, ldap, email, subjectId);
    }
    return new Requester(Requester.Kind.SERVICE_ACCOUNT, null, email, subjectId);
  }

  /**
   * Emits the audience of the first assertion this revision verifies.
   *
   * <p>Cloud Run's direct IAP integration does not document its audience format, so this is how the
   * value destined for {@code app.iap.expected-audience} is discovered. Logged once per revision to
   * keep it out of the steady-state log volume.
   */
  private void logClaimsOnce(Jwt assertion) {
    if (claimsLogged.compareAndSet(false, true)) {
      log.info(
          "IAP assertion claims observed — iss: {}, aud: {}. Pin the audience via IAP_AUDIENCE"
              + " and set app.iap.enforce-audience=true.",
          assertion.getIssuer(),
          assertion.getAudience());
    }
  }
}
