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

/**
 * The authenticated principal behind a single inbound HTTP request.
 *
 * <p>Produced by {@link IapAssertionVerifier} from the signed IAP assertion and exposed to
 * controllers through {@link RequesterContext}. A {@code Requester} is only as trustworthy as the
 * assertion it came from; an instance whose {@link #kind()} is {@link Kind#UNKNOWN} carries no
 * verified identity at all and must never be used to attribute a data access.
 *
 * @param kind how the caller was classified
 * @param ldap the Googler's LDAP (the local part of an {@code @google.com} address), or null
 * @param email the caller's full email address, or null when unauthenticated
 * @param subjectId the stable IAP subject identifier, or null when unauthenticated
 */
public record Requester(Kind kind, String ldap, String email, String subjectId) {

  /** The category of caller behind a request. */
  public enum Kind {
    /** A signed-in Googler. Only this kind carries a non-null {@code ldap}. */
    GOOGLER,
    /** A Google service account, in practice the Cloud Tasks runtime identity. */
    SERVICE_ACCOUNT,
    /** No verifiable identity was present on the request. */
    UNKNOWN
  }

  /** Request attribute key under which the resolved requester is cached for the current request. */
  static final String REQUEST_ATTRIBUTE = "com.bulkaibcd.web.Requester";

  private static final Requester UNKNOWN = new Requester(Kind.UNKNOWN, null, null, null);

  /** Returns the singleton requester used when no verifiable identity is present. */
  public static Requester unknown() {
    return UNKNOWN;
  }

  /** Returns true when this requester is a signed-in Googler with a usable LDAP. */
  public boolean isGoogler() {
    return kind == Kind.GOOGLER && ldap != null && !ldap.isBlank();
  }

  /**
   * Returns the value to persist as {@code requesterId}, or null when the caller cannot be
   * attributed.
   *
   * <p>Googlers are recorded by bare LDAP so that Firestore documents and log entries stay readable
   * (for example {@code "jdoe"}). Any other authenticated principal falls back to its full email,
   * which keeps attribution unambiguous if non-Googler accounts are ever granted access.
   */
  public String attributionId() {
    if (isGoogler()) {
      return ldap;
    }
    return email == null || email.isBlank() ? null : email;
  }
}
