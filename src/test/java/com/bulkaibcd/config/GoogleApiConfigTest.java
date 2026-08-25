package com.bulkaibcd.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.client.http.HttpTransport;
import org.junit.jupiter.api.Test;

class GoogleApiConfigTest {

  @Test
  void createsTrustedTransport() throws Exception {
    HttpTransport transport = GoogleApiConfig.transport();
    assertThat(transport).isNotNull();
  }
}

