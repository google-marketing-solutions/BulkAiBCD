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
