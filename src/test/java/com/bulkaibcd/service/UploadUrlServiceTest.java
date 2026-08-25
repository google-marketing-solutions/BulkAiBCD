package com.bulkaibcd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.client.GcsClient;
import com.google.cloud.storage.BlobInfo;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UploadUrlServiceTest {

  private GcsClient gcsClient;
  private UploadUrlService service;

  @BeforeEach
  void setUp() {
    gcsClient = mock(GcsClient.class);
    service = new UploadUrlService(gcsClient);
    ReflectionTestUtils.setField(service, "bucket", "test-uploads-bucket");
  }

  @Test
  void createReturnsSignedUploadUrl() throws Exception {
    URL signedUrl = new URL("https://storage.googleapis.com/test-uploads-bucket/obj?signed");
    when(gcsClient.signUrl(any(BlobInfo.class), eq(15L), eq(TimeUnit.MINUTES), any(), any(), any()))
        .thenReturn(signedUrl);

    UploadUrlService.SignedUploadUrl result = service.create("my video.mp4", "video/mp4");
    assertThat(result.url()).isEqualTo(signedUrl.toString());
    assertThat(result.gcsObjectId()).startsWith("test-uploads-bucket/");
    assertThat(result.gcsObjectId()).contains("my_video.mp4");
  }

  @Test
  void createWithNullFilenameAndContentTypeUsesDefaults() throws Exception {
    URL signedUrl = new URL("https://storage.googleapis.com/test-uploads-bucket/obj?signed");
    when(gcsClient.signUrl(any(BlobInfo.class), eq(15L), eq(TimeUnit.MINUTES), any(), any(), any()))
        .thenReturn(signedUrl);

    UploadUrlService.SignedUploadUrl result = service.create(null, null);
    assertThat(result.url()).isEqualTo(signedUrl.toString());
    assertThat(result.gcsObjectId()).contains("video.mp4");
  }

  @Test
  void deleteParsesBucketAndObjectAndCallsGcsClient() {
    service.delete("test-uploads-bucket/path/to/video.mp4");
    verify(gcsClient).deleteObject("test-uploads-bucket", "path/to/video.mp4");

    service.delete(null);
    service.delete("");
    service.delete("no-slash-id");

    doThrow(new RuntimeException("delete error")).when(gcsClient).deleteObject("b", "o");
    // Should swallow exception
    service.delete("b/o");
  }
}
