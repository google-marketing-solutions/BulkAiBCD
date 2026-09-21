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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Records one attributable audit entry for every API call.
 *
 * <p>Sitting in the filter chain rather than in the controllers means an endpoint cannot be added
 * later that quietly escapes the audit trail. The resolved caller is also published into the SLF4J
 * {@link MDC}, so the log statements the services already emit gain the caller's LDAP without any
 * of them being modified.
 *
 * <p>Only the actor, the resource identifiers and the outcome are recorded. Request and response
 * bodies, OAuth tokens and signed URLs are deliberately never touched: an audit log is read by more
 * people than the data it describes.
 */
@Component
public class AccessLogFilter extends OncePerRequestFilter {

  /**
   * Dedicated logger name so the audit stream can be routed to its own Cloud Logging log and given
   * a retention policy independent of ordinary application chatter.
   */
  private static final Logger ACCESS_LOG = LoggerFactory.getLogger("bulkaibcd-access");

  private static final String API_PREFIX = "/api/v2/";

  /** Analysis identifiers are {@code UUID.randomUUID()} values, so they are matched by shape. */
  private static final Pattern ANALYSIS_ID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private static final String MDC_ACTOR_LDAP = "actorLdap";
  private static final String MDC_ACTOR_KIND = "actorKind";
  private static final String MDC_ANALYSIS_ID = "analysisId";

  private final IapAssertionVerifier verifier;

  public AccessLogFilter(IapAssertionVerifier verifier) {
    this.verifier = verifier;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(API_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Requester requester = verifier.resolve(request);
    request.setAttribute(Requester.REQUEST_ATTRIBUTE, requester);

    String analysisId = extractAnalysisId(request.getRequestURI());
    MDC.put(MDC_ACTOR_KIND, requester.kind().name());
    putIfPresent(MDC_ACTOR_LDAP, requester.attributionId());
    putIfPresent(MDC_ANALYSIS_ID, analysisId);

    long startNanos = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      writeAccessEntry(request, response, requester, analysisId, startNanos);
      MDC.remove(MDC_ACTOR_KIND);
      MDC.remove(MDC_ACTOR_LDAP);
      MDC.remove(MDC_ANALYSIS_ID);
    }
  }

  private static void writeAccessEntry(
      HttpServletRequest request,
      HttpServletResponse response,
      Requester requester,
      String analysisId,
      long startNanos) {
    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
    ACCESS_LOG.info(
        "event={} actor={} actorKind={} method={} path={} analysisId={} status={} latencyMs={}",
        isReadOnly(request.getMethod()) ? "DATA_READ" : "DATA_WRITE",
        requester.attributionId() == null ? "UNATTRIBUTED" : requester.attributionId(),
        requester.kind(),
        request.getMethod(),
        request.getRequestURI(),
        analysisId == null ? "-" : analysisId,
        response.getStatus(),
        latencyMs);
  }

  private static boolean isReadOnly(String method) {
    return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
  }

  private static String extractAnalysisId(String requestUri) {
    Matcher matcher = ANALYSIS_ID.matcher(requestUri);
    return matcher.find() ? matcher.group() : null;
  }

  private static void putIfPresent(String key, String value) {
    if (value != null && !value.isBlank()) {
      MDC.put(key, value);
    }
  }
}
