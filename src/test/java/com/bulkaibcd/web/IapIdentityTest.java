package com.bulkaibcd.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class IapIdentityTest {

  @Test
  void currentUserEmailWithIapPrefixStripsPrefix() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("X-Goog-Authenticated-User-Email"))
        .thenReturn("accounts.google.com:alice@example.com");

    IapIdentity identity = new IapIdentity(req);
    assertThat(identity.currentUserEmail()).isEqualTo("alice@example.com");
  }

  @Test
  void currentUserEmailWithoutIapPrefixReturnsRaw() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("X-Goog-Authenticated-User-Email")).thenReturn("alice@example.com");

    IapIdentity identity = new IapIdentity(req);
    assertThat(identity.currentUserEmail()).isEqualTo("alice@example.com");
  }

  @Test
  void currentUserEmailNullOrBlankReturnsNull() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("X-Goog-Authenticated-User-Email")).thenReturn(null);
    IapIdentity identity = new IapIdentity(req);
    assertThat(identity.currentUserEmail()).isNull();

    when(req.getHeader("X-Goog-Authenticated-User-Email")).thenReturn("   ");
    assertThat(identity.currentUserEmail()).isNull();
  }

  @Test
  void currentUserIdWithIapPrefixStripsPrefix() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("X-Goog-Authenticated-User-Id"))
        .thenReturn("accounts.google.com:1234567890");

    IapIdentity identity = new IapIdentity(req);
    assertThat(identity.currentUserId()).isEqualTo("1234567890");
  }

  @Test
  void currentUserIdNullOrBlankReturnsNull() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("X-Goog-Authenticated-User-Id")).thenReturn(null);
    IapIdentity identity = new IapIdentity(req);
    assertThat(identity.currentUserId()).isNull();

    when(req.getHeader("X-Goog-Authenticated-User-Id")).thenReturn("");
    assertThat(identity.currentUserId()).isNull();
  }
}

