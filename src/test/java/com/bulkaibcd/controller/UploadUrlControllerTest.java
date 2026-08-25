package com.bulkaibcd.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bulkaibcd.service.UploadUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UploadUrlControllerTest {

  private UploadUrlService uploadUrlService;
  private UploadUrlController controller;

  @BeforeEach
  void setUp() {
    uploadUrlService = mock(UploadUrlService.class);
    controller = new UploadUrlController(uploadUrlService);
  }

  @Test
  void getUploadUrlDelegatesToUploadUrlService() {
    UploadUrlController.UploadUrlRequest req = new UploadUrlController.UploadUrlRequest();
    req.setFilename("video.mp4");
    req.setContentType("video/mp4");

    UploadUrlService.SignedUploadUrl signed =
        new UploadUrlService.SignedUploadUrl("https://upload.url", "bucket/video.mp4");
    when(uploadUrlService.create("video.mp4", "video/mp4")).thenReturn(signed);

    UploadUrlService.SignedUploadUrl result = controller.getUploadUrl(req);
    assertThat(result).isSameAs(signed);
    verify(uploadUrlService).create("video.mp4", "video/mp4");
  }
}
