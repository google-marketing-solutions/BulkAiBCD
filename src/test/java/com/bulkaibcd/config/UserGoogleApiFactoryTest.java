package com.bulkaibcd.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.api.services.drive.Drive;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.slides.v1.Slides;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserGoogleApiFactoryTest {

  private UserGoogleApiFactory factory;

  @BeforeEach
  void setUp() {
    factory = new UserGoogleApiFactory();
  }

  @Test
  void createsDriveClientWithValidToken() throws Exception {
    Drive drive = factory.drive("valid-test-access-token");
    assertThat(drive).isNotNull();
    assertThat(drive.getApplicationName()).isEqualTo("bulkaibcd");
  }

  @Test
  void createsSlidesClientWithValidToken() throws Exception {
    Slides slides = factory.slides("valid-test-access-token");
    assertThat(slides).isNotNull();
    assertThat(slides.getApplicationName()).isEqualTo("bulkaibcd");
  }

  @Test
  void createsSheetsClientWithValidToken() throws Exception {
    Sheets sheets = factory.sheets("valid-test-access-token");
    assertThat(sheets).isNotNull();
    assertThat(sheets.getApplicationName()).isEqualTo("bulkaibcd");
  }

  @Test
  void throwsExceptionWhenTokenIsNullOrBlank() {
    assertThatThrownBy(() -> factory.drive(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("User access token is required");

    assertThatThrownBy(() -> factory.slides(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("User access token is required");

    assertThatThrownBy(() -> factory.sheets("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("User access token is required");
  }
}

