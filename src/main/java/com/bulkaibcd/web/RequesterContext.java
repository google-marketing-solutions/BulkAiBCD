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

/** The verified caller behind the HTTP request currently being handled. */
@Component
@RequestScope
public class RequesterContext {

  private final HttpServletRequest request;
  private final IapAssertionVerifier verifier;

  public RequesterContext(HttpServletRequest request, IapAssertionVerifier verifier) {
    this.request = request;
    this.verifier = verifier;
  }

  /**
   * Returns the caller behind the current request.
   *
   * <p>{@link AccessLogFilter} resolves and caches the requester before routing, so this normally
   * reads a request attribute rather than re-verifying the assertion. The fallback path exists for
   * requests that bypass the filter, such as those raised directly in tests.
   *
   * @return the verified caller, never null but possibly {@link Requester#unknown()}
   */
  public Requester current() {
    Object cached = request.getAttribute(Requester.REQUEST_ATTRIBUTE);
    if (cached instanceof Requester requester) {
      return requester;
    }
    Requester resolved = verifier.resolve(request);
    request.setAttribute(Requester.REQUEST_ATTRIBUTE, resolved);
    return resolved;
  }
}
