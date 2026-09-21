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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessLogFilterTest {

  private static final String ANALYSIS_ID = "a7f3c9e1-1111-2222-3333-444455556666";

  private Logger accessLogger;
  private ListAppender<ILoggingEvent> appender;
  private IapAssertionVerifier verifier;
  private AccessLogFilter filter;

  @BeforeEach
  void setUp() {
    accessLogger = (Logger) LoggerFactory.getLogger("bulkaibcd-access");
    appender = new ListAppender<>();
    appender.start();
    accessLogger.addAppender(appender);
    accessLogger.setLevel(Level.INFO);

    verifier = mock(IapAssertionVerifier.class);
    filter = new AccessLogFilter(verifier);
  }

  @AfterEach
  void tearDown() {
    accessLogger.detachAppender(appender);
    MDC.clear();
  }

  @Test
  void writesExactlyOneEntryPerRequestNamingTheActor() throws Exception {
    givenRequester(new Requester(Requester.Kind.GOOGLER, "jdoe", "jdoe@google.com", "sub"));
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v2/input/submit");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(appender.list).hasSize(1);
    String entry = appender.list.get(0).getFormattedMessage();
    assertThat(entry).contains("event=DATA_WRITE");
    assertThat(entry).contains("actor=jdoe");
    assertThat(entry).contains("actorKind=GOOGLER");
    assertThat(entry).contains("path=/api/v2/input/submit");
    assertThat(entry).contains("status=200");
  }

  @Test
  void classifiesReadsSeparatelyAndCapturesTheAnalysisId() throws Exception {
    givenRequester(new Requester(Requester.Kind.GOOGLER, "jdoe", "jdoe@google.com", "sub"));
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v2/input/" + ANALYSIS_ID);

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    String entry = appender.list.get(0).getFormattedMessage();
    assertThat(entry).contains("event=DATA_READ");
    assertThat(entry).contains("analysisId=" + ANALYSIS_ID);
  }

  @Test
  void recordsUnattributedCallersRatherThanSkippingThem() throws Exception {
    givenRequester(Requester.unknown());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/input/list");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    String entry = appender.list.get(0).getFormattedMessage();
    assertThat(entry).contains("actor=UNATTRIBUTED");
    assertThat(entry).contains("actorKind=UNKNOWN");
  }

  @Test
  void cachesTheResolvedRequesterOnTheRequest() throws Exception {
    Requester requester =
        new Requester(Requester.Kind.GOOGLER, "jdoe", "jdoe@google.com", "sub");
    givenRequester(requester);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/input/list");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(request.getAttribute(Requester.REQUEST_ATTRIBUTE)).isEqualTo(requester);
  }

  @Test
  void clearsMdcSoTheLdapCannotLeakOntoAPooledThread() throws Exception {
    givenRequester(new Requester(Requester.Kind.GOOGLER, "jdoe", "jdoe@google.com", "sub"));

    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v2/input/list"),
        new MockHttpServletResponse(),
        new MockFilterChain());

    assertThat(MDC.get("actorLdap")).isNull();
    assertThat(MDC.get("actorKind")).isNull();
  }

  @Test
  void leavesNonApiTrafficAlone() {
    MockHttpServletRequest staticAsset = new MockHttpServletRequest("GET", "/index.html");
    MockHttpServletRequest apiCall = new MockHttpServletRequest("GET", "/api/v2/input/list");

    assertThat(filter.shouldNotFilter(staticAsset)).isTrue();
    assertThat(filter.shouldNotFilter(apiCall)).isFalse();
  }

  private void givenRequester(Requester requester) {
    when(verifier.resolve(any(HttpServletRequest.class))).thenReturn(requester);
  }
}
