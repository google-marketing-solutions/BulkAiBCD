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

package com.bulkaibcd.config;

import com.google.cloud.hybrid.connect.c2pauthorizer.client.C2PAuthorizerClientEnvironment;
import com.google.cloud.hybrid.connect.c2pauthorizer.client.HelheimTokenRefresher;
import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A configuration provider for Boq Hybrid API client, HTTP transport, and token refreshers.
 */
@Configuration
@Slf4j
public class BoqHybridConfig {

  /**
   * Builds an {@link HttpClient} configured with standard connect timeouts for Hybrid API RPCs.
   *
   * @return the configured HTTP client instance
   */
  @Bean
  public HttpClient boqHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build();
  }

  /**
   * Builds a {@link HelheimTokenRefresher} configured to periodically refresh tokens for Boq RPCs.
   *
   * @param serviceName the registered OnePlatform service name ending in .hybrid.googleapis.com
   * @return the configured Helheim token refresher instance
   */
  @Bean
  public HelheimTokenRefresher helheimTokenRefresher(
      @Value("${app.boq.hybrid-api-service-name:staging-bulkaibcd.hybrid.googleapis.com}")
          String serviceName) {
    try {
      return HelheimTokenRefresher.builder()
          .setServiceName(serviceName)
          .setEnvironment(C2PAuthorizerClientEnvironment.STAGING)
          .build();
    } catch (Exception e) {
      throw new IllegalStateException("Could not initialize HelheimTokenRefresher", e);
    }
  }
}
