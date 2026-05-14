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
