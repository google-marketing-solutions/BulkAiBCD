package com.bulkaibcd.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;

class SecurityConfigTest {

  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(SecurityAutoConfiguration.class, SecurityConfig.class));

  @Test
  void testSecurityFilterChainBeanExists() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(SecurityFilterChain.class);
        });
  }
}
