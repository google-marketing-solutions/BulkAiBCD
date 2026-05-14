package com.bulkaibcd.controller;

import com.bulkaibcd.service.UploadUrlService;
import com.bulkaibcd.service.UploadUrlService.SignedUploadUrl;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/input")
@RequiredArgsConstructor
@Slf4j
public class UploadUrlController {

  private final UploadUrlService uploadUrlService;

  @Data
  public static class UploadUrlRequest {
    private String filename;
    private String contentType;
  }

  @PostMapping("/upload-url")
  public SignedUploadUrl getUploadUrl(@RequestBody UploadUrlRequest body) {
    return uploadUrlService.create(body.getFilename(), body.getContentType());
  }
}
