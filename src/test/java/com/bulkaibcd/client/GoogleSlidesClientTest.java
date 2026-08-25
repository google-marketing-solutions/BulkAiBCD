package com.bulkaibcd.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.config.UserGoogleApiFactory;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.slides.v1.Slides;
import com.google.api.services.slides.v1.model.Page;
import com.google.api.services.slides.v1.model.Presentation;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GoogleSlidesClientTest {

  private UserGoogleApiFactory userApis;
  private GoogleSlidesClient client;

  @BeforeEach
  void setUp() {
    userApis = mock(UserGoogleApiFactory.class);
    client = new GoogleSlidesClient(userApis);
  }

  @Test
  void testPitchDeckParams() {
    GoogleSlidesClient.PitchDeckParams params =
        GoogleSlidesClient.PitchDeckParams.builder()
            .userAccessToken("token-123")
            .brandName("Brand")
            .marketingObjective("awareness")
            .analysisType("standard")
            .allFeatures(List.of("f1", "f2"))
            .videos(List.of())
            .build();

    assertThat(params.userAccessToken()).isEqualTo("token-123");
    assertThat(params.brandName()).isEqualTo("Brand");
    assertThat(params.marketingObjective()).isEqualTo("awareness");
    assertThat(params.analysisType()).isEqualTo("standard");
    assertThat(params.allFeatures()).containsExactly("f1", "f2");
    assertThat(params.videos()).isEmpty();
  }

  @Test
  void testResolveThumbnailUrlWithExplicitThumbnail() {
    VideoMetadataEntity video =
        VideoMetadataEntity.builder()
            .thumbnailUrl("https://custom.thumbnail.com/thumb.jpg")
            .build();

    String thumb = ReflectionTestUtils.invokeMethod(client, "resolveThumbnailUrl", video);
    assertThat(thumb).isEqualTo("https://custom.thumbnail.com/thumb.jpg");
  }

  @Test
  void testResolveThumbnailUrlWithYouTubeUrl() {
    VideoMetadataEntity video =
        VideoMetadataEntity.builder()
            .videoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            .build();

    String thumb = ReflectionTestUtils.invokeMethod(client, "resolveThumbnailUrl", video);
    assertThat(thumb).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
  }

  @Test
  void testResolveThumbnailUrlFallback() {
    VideoMetadataEntity video = VideoMetadataEntity.builder().build();

    String thumb = ReflectionTestUtils.invokeMethod(client, "resolveThumbnailUrl", video);
    assertThat(thumb).isEqualTo("https://www.gstatic.com/images/icons/material/system/2x/video_library_black_48dp.png");
  }

  @Test
  void testCalculateAverageScore() {
    VideoMetadataEntity video =
        VideoMetadataEntity.builder()
            .aScore(80)
            .bScore(60)
            .cScore(70)
            .dScore(90)
            .build();

    int score = ReflectionTestUtils.invokeMethod(client, "calculateAverageScore", video);
    assertThat(score).isEqualTo(75);

    VideoMetadataEntity empty = VideoMetadataEntity.builder().build();
    int emptyScore = ReflectionTestUtils.invokeMethod(client, "calculateAverageScore", empty);
    assertThat(emptyScore).isEqualTo(0);
  }
}
