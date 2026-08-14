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
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Reads the identity that IAP attached to the incoming request.
 *
 * <p>IAP (after it verifies the user's Google login and IAM role) stamps two
 * headers on every request it forwards to Cloud Run:
 *
 * <ul>
 *   <li>{@code X-Goog-Authenticated-User-Email} — {@code accounts.google.com:alice@example.com}
 *   <li>{@code X-Goog-Authenticated-User-Id} — {@code accounts.google.com:<numeric sub>}
 * </ul>
 *
 * <p>This helper exists so controllers can replace hardcoded {@code "default-user"}
 * requester IDs with the actual signed-in email, for audit logging and per-user
 * data scoping. Request-scoped so the injection reflects the current HTTP call.
 */
@Component
@RequestScope
public class IapIdentity {

  private static final String IAP_EMAIL_HEADER = "X-Goog-Authenticated-User-Email";
  private static final String IAP_ID_HEADER = "X-Goog-Authenticated-User-Id";
  private static final String IAP_PREFIX = "accounts.google.com:";

  private final HttpServletRequest request;

  public IapIdentity(HttpServletRequest request) {
    this.request = request;
  }

  /** Signed-in user's email, or null when running outside an IAP-fronted deploy (dev). */
  public String currentUserEmail() {
    return stripPrefix(request.getHeader(IAP_EMAIL_HEADER));
  }

  /** Signed-in user's stable Google account id (sub), or null. */
  public String currentUserId() {
    return stripPrefix(request.getHeader(IAP_ID_HEADER));
  }

  private static String stripPrefix(String raw) {
    if (raw == null || raw.isBlank()) return null;
    return raw.startsWith(IAP_PREFIX) ? raw.substring(IAP_PREFIX.length()) : raw;
  }
}
