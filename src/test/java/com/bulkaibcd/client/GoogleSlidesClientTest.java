package com.bulkaibcd.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bulkaibcd.config.UserGoogleApiFactory;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.slides.v1.Slides;
import com.google.api.services.slides.v1.model.Page;
import com.google.api.services.slides.v1.model.Presentation;
import com.google.cloud.storage.BlobInfo;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class GoogleSlidesClientTest {

  private UserGoogleApiFactory userApis;
  private GcsClient gcsClient;
  private GoogleSlidesClient client;

  @BeforeEach
  void setUp() {
    userApis = mock(UserGoogleApiFactory.class);
    gcsClient = mock(GcsClient.class);
    client = new GoogleSlidesClient(userApis, gcsClient, "test-bucket");
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
  void testResolveThumbnailUrlWithDataUriGeneratesSignedUrl() throws Exception {
    URL signedUrl = new URL("https://storage.googleapis.com/test-bucket/thumbnails/thumb.jpg?signed");
    when(gcsClient.signUrl(any(BlobInfo.class), anyLong(), any(TimeUnit.class), any(), any()))
        .thenReturn(signedUrl);

    VideoMetadataEntity video =
        VideoMetadataEntity.builder()
            .thumbnailUrl("data:image/jpeg;base64,/9j/4AAQSkZJRg==")
            .build();

    String thumb = ReflectionTestUtils.invokeMethod(client, "resolveThumbnailUrl", video);
    assertThat(thumb).isEqualTo(signedUrl.toString());
  }

  @Test
  void testResolveThumbnailUrlWithDataUriFallbackWhenGcsNotConfigured() {
    GoogleSlidesClient clientNoBucket = new GoogleSlidesClient(userApis, gcsClient, "");
    VideoMetadataEntity video =
        VideoMetadataEntity.builder()
            .thumbnailUrl("data:image/jpeg;base64,/9j/4AAQSkZJRg==")
            .build();

    String thumb = ReflectionTestUtils.invokeMethod(clientNoBucket, "resolveThumbnailUrl", video);
    assertThat(thumb).isEqualTo("https://www.gstatic.com/images/icons/material/system/2x/video_library_black_48dp.png");
  }

  @Test
  void testResolveThumbnailUrlWithOversizedUrlFallsBackToDefault() {
    String longUrl = "https://example.com/" + "a".repeat(2050);
    VideoMetadataEntity video =
        VideoMetadataEntity.builder()
            .thumbnailUrl(longUrl)
            .build();

    String thumb = ReflectionTestUtils.invokeMethod(client, "resolveThumbnailUrl", video);
    assertThat(thumb).isEqualTo("https://www.gstatic.com/images/icons/material/system/2x/video_library_black_48dp.png");
  }

  @Test
  void testResolveThumbnailUrlWithDriveUrlFallsBackGracefullyOnFailure() {
    VideoMetadataEntity video =
        VideoMetadataEntity.builder()
            .thumbnailUrl("https://lh3.googleusercontent.com/u/0/d/invalid-id=s220")
            .build();

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

  @Test
  void testResolveAssetUrlPrefersVideoUrlAndNeverSigns() {
    VideoMetadataEntity video =
        VideoMetadataEntity.builder()
            .videoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            .gcsObjectId("bucket/object.mp4")
            .build();

    String url =
        ReflectionTestUtils.invokeMethod(client, "resolveAssetUrl", video, new HashMap<>());

    assertThat(url).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
    verifyNoInteractions(gcsClient);
  }

  /** The regression: uploaded files have no videoUrl, so the link must come from gcsObjectId. */
  @Test
  void testResolveAssetUrlSignsGcsObjectForUploadedFile() throws Exception {
    URL signedUrl = new URL("https://storage.googleapis.com/bucket/object.mp4?X-Goog-Signature=a");
    when(gcsClient.signUrl(any(BlobInfo.class), anyLong(), any(TimeUnit.class), any(), any()))
        .thenReturn(signedUrl);

    VideoMetadataEntity video =
        VideoMetadataEntity.builder()
            .sourceType("file")
            .gcsObjectId("bucket/nested/path/object.mp4")
            .build();

    String url =
        ReflectionTestUtils.invokeMethod(client, "resolveAssetUrl", video, new HashMap<>());

    assertThat(url).isEqualTo(signedUrl.toString());

    ArgumentCaptor<BlobInfo> blob = ArgumentCaptor.forClass(BlobInfo.class);
    ArgumentCaptor<Long> ttl = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<TimeUnit> unit = ArgumentCaptor.forClass(TimeUnit.class);
    verify(gcsClient).signUrl(blob.capture(), ttl.capture(), unit.capture(), any(), any());

    // Only the first slash separates bucket from object; the rest belongs to the object path.
    assertThat(blob.getValue().getBucket()).isEqualTo("bucket");
    assertThat(blob.getValue().getName()).isEqualTo("nested/path/object.mp4");
    assertThat(ttl.getValue()).isEqualTo(7L);
    assertThat(unit.getValue()).isEqualTo(TimeUnit.DAYS);
  }

  @Test
  void testResolveAssetUrlSignsEachObjectOnlyOnce() throws Exception {
    URL signedUrl = new URL("https://storage.googleapis.com/bucket/object.mp4?X-Goog-Signature=a");
    when(gcsClient.signUrl(any(BlobInfo.class), anyLong(), any(TimeUnit.class), any(), any()))
        .thenReturn(signedUrl);

    VideoMetadataEntity video =
        VideoMetadataEntity.builder().gcsObjectId("bucket/object.mp4").build();
    Map<String, String> cache = new HashMap<>();

    String first = ReflectionTestUtils.invokeMethod(client, "resolveAssetUrl", video, cache);
    String second = ReflectionTestUtils.invokeMethod(client, "resolveAssetUrl", video, cache);

    // Summary and detail slides must point at an identical URL, not two distinct signatures.
    assertThat(first).isEqualTo(second);
    verify(gcsClient, times(1))
        .signUrl(any(BlobInfo.class), anyLong(), any(TimeUnit.class), any(), any());
  }

  @Test
  void testResolveAssetUrlReturnsNullWhenNoSourceAvailable() {
    VideoMetadataEntity video = VideoMetadataEntity.builder().build();

    String url =
        ReflectionTestUtils.invokeMethod(client, "resolveAssetUrl", video, new HashMap<>());

    assertThat(url).isNull();
    verifyNoInteractions(gcsClient);
  }

  @Test
  void testResolveAssetUrlReturnsNullForMalformedGcsObjectId() {
    VideoMetadataEntity video =
        VideoMetadataEntity.builder().gcsObjectId("no-slash-here").build();

    String url =
        ReflectionTestUtils.invokeMethod(client, "resolveAssetUrl", video, new HashMap<>());

    assertThat(url).isNull();
    verifyNoInteractions(gcsClient);
  }

  /** A signing outage must blank the link, not abort the whole deck. */
  @Test
  void testResolveAssetUrlDegradesGracefullyWhenSigningFails() {
    when(gcsClient.signUrl(any(BlobInfo.class), anyLong(), any(TimeUnit.class), any(), any()))
        .thenThrow(new IllegalStateException("no signer available"));

    VideoMetadataEntity video =
        VideoMetadataEntity.builder().gcsObjectId("bucket/object.mp4").build();
    Map<String, String> cache = new HashMap<>();

    String url = ReflectionTestUtils.invokeMethod(client, "resolveAssetUrl", video, cache);
    assertThat(url).isNull();

    // The failure is cached too, so a broken signer is not retried once per slide.
    ReflectionTestUtils.invokeMethod(client, "resolveAssetUrl", video, cache);
    verify(gcsClient, times(1))
        .signUrl(any(BlobInfo.class), anyLong(), any(TimeUnit.class), any(), any());
  }

  /** A signed URL is hundreds of characters long, so it is never rendered as slide text. */
  @Test
  void testAssetLinkDisplayTextUsesLabelForSignedGcsUrl() {
    String signed =
        "https://storage.googleapis.com/bucket/object.mp4?X-Goog-Signature=" + "a".repeat(512);

    String text = ReflectionTestUtils.invokeMethod(client, "assetLinkDisplayText", signed);

    assertThat(text).isEqualTo("View Video");
  }

  /** The label is identical for URL-sourced videos, so the deck reads consistently. */
  @Test
  void testAssetLinkDisplayTextUsesSameLabelForYouTubeAndDrive() {
    String youtube = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    String drive = "https://drive.google.com/file/d/abc123/view";

    String youtubeText = ReflectionTestUtils.invokeMethod(client, "assetLinkDisplayText", youtube);
    String driveText = ReflectionTestUtils.invokeMethod(client, "assetLinkDisplayText", drive);

    assertThat(youtubeText).isEqualTo("View Video");
    assertThat(driveText).isEqualTo("View Video");
  }

  @Test
  void testAssetLinkDisplayTextIsBlankWhenNoUrlResolved() {
    String text = ReflectionTestUtils.invokeMethod(client, "assetLinkDisplayText", (String) null);

    assertThat(text).isEmpty();
  }

}
