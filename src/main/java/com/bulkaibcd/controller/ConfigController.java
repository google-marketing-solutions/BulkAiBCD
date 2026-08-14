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

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only config exposed to the UI so deployment-specific values (e.g. the
 * runtime service-account email the user must share Drive files with) don't
 * have to be hardcoded in the Angular bundle.
 */
@RestController
@RequestMapping("/api/v2/config")
public class ConfigController {

  @Value("${google.cloud.tasks.service-account:}")
  private String driveIngestServiceAccount;

  @Value("${google.cloud.project.id:}")
  private String projectId;

  @GetMapping
  public Map<String, String> get() {
    return Map.of(
        "driveIngestServiceAccount", driveIngestServiceAccount == null ? "" : driveIngestServiceAccount,
        "projectId", projectId == null ? "" : projectId);
  }
}
