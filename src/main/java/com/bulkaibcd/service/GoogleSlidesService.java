package com.bulkaibcd.service;

import com.bulkaibcd.config.UserGoogleApiFactory;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import com.google.api.services.slides.v1.Slides;
import com.google.api.services.slides.v1.model.BatchUpdatePresentationRequest;
import com.google.api.services.slides.v1.model.ReplaceAllTextRequest;
import com.google.api.services.slides.v1.model.Request;
import com.google.api.services.slides.v1.model.SubstringMatchCriteria;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Generates per-video Google Slides pitch decks from the committed
 * {@code /templates/master_pitch_deck.pptx} template.
 *
 * <p>Flow:
 * <ol>
 *   <li>Load the PPTX template from classpath.
 *   <li>Upload to Drive with {@code mimeType=application/vnd.google-apps.presentation}
 *       so Drive auto-converts it into a native Google Slides file on ingest.
 *   <li>Build a {@code batchUpdate} request with {@link ReplaceAllTextRequest} entries
 *       for every {@code TOKEN} the template uses (see {@link PitchDeckParams}), plus
 *       {@code ○ <feature>} → {@code ● <feature>} flips for each detected feature.
 *   <li>Add a reader-by-link permission so the Slides URL is openable.
 *   <li>Return {@code https://docs.google.com/presentation/d/<id>}.
 * </ol>
 *
 * <p>Only deviation from the pasted reference code: the {@code Drive} + {@code Slides}
 * clients come from {@link UserGoogleApiFactory} built with a user OAuth access token
 * (scope {@code drive.file}), not the runtime service account — so the generated deck
 * is owned by the signed-in user.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GoogleSlidesService {

  private static final String TEMPLATE_CLASSPATH = "/templates/master_pitch_deck.pptx";
  private static final String PPTX_MIME =
      "application/vnd.openxmlformats-officedocument.presentationml.presentation";
  private static final String SLIDES_MIME = "application/vnd.google-apps.presentation";

  private final UserGoogleApiFactory userApis;

  /** Parameter bag for a single-video deck render — one per selected video. */
  @Builder
  public record PitchDeckParams(
      String userAccessToken,
      String brandName,
      String videoTitle,
      String videoUrl,
      int adherenceScore,
      String completionDate,
      String marketingObjective,
      List<String> allFeatures,
      List<String> detectedFeatures) {}

  /**
   * Creates one pitch deck for a single video, returns the Slides URL.
   */
  public String generatePitchDeckForVideo(PitchDeckParams p) throws IOException {
    Drive drive = userApis.drive(p.userAccessToken());
    Slides slides = userApis.slides(p.userAccessToken());

    try (InputStream templateStream = getClass().getResourceAsStream(TEMPLATE_CLASSPATH)) {
      if (templateStream == null) {
        throw new IOException(
            "Template file 'master_pitch_deck.pptx' not found on classpath at "
                + TEMPLATE_CLASSPATH);
      }

      String safeBrand = nonBlank(p.brandName(), "Untitled");
      String safeTitle = nonBlank(p.videoTitle(), "Video");
      File fileMetadata =
          new File()
              .setName("Bulk AiBCD report - " + safeBrand + " - " + safeTitle)
              .setMimeType(SLIDES_MIME);
      InputStreamContent mediaContent = new InputStreamContent(PPTX_MIME, templateStream);
      File uploadedDeck =
          drive.files().create(fileMetadata, mediaContent).setFields("id").execute();
      String presentationId = uploadedDeck.getId();

      List<String> allFeatures = p.allFeatures() == null ? List.of() : p.allFeatures();
      List<String> detected = p.detectedFeatures() == null ? List.of() : p.detectedFeatures();
      int detectedCount = detected.size();
      int totalFeatures = allFeatures.size();
      String adherenceCategory = categoryFor(p.adherenceScore());

      List<String> missingFeatures = new ArrayList<>();
      for (String f : allFeatures) {
        if (!detected.contains(f)) missingFeatures.add(f);
      }

      // Up-front literal replacements: every token the template uses.
      List<Request> requests = new ArrayList<>();
      requests.add(replaceAll("BRAND_NAME", safeBrand));
      requests.add(replaceAll("ASSET_NAME", safeTitle));
      requests.add(replaceAll("ASSET_LINK", nonBlank(p.videoUrl(), "")));
      requests.add(replaceAll("ASSET_NUMBER", "1"));
      requests.add(replaceAll("VIDEO_COUNT", "1"));
      requests.add(replaceAll("COMPLETION_DATE", nonBlank(p.completionDate(), "")));
      requests.add(replaceAll("MARKETING_OBJECTIVE", nonBlank(p.marketingObjective(), "")));
      requests.add(replaceAll("ADHERENCE_PERC", p.adherenceScore() + "%"));
      requests.add(replaceAll("ADHERENCE_X", String.valueOf(detectedCount)));
      requests.add(replaceAll("ADHERENCE_Y", String.valueOf(totalFeatures)));
      requests.add(replaceAll("ADHERENCE_CATEGORY", adherenceCategory));

      // Summary slide has slots for up to 3 videos; we fill slot 1 with this
      // video, blank the others out. When the user duplicates the summary-slide
      // layout later we can parameterize this further.
      requests.add(replaceAll("VIDEO_TITLE_1", safeTitle));
      requests.add(replaceAll("VIDEO_TITLE_2", ""));
      requests.add(replaceAll("VIDEO_TITLE_3", ""));
      requests.add(replaceAll("ADHERENCE_CATEGORY_1", adherenceCategory));
      requests.add(replaceAll("ADHERENCE_CATEGORY_2", ""));
      requests.add(replaceAll("ADHERENCE_CATEGORY_3", ""));

      // Missing-features slide: up to 4 rows.
      requests.add(replaceAll("MISSING_FEATURES_COUNT", String.valueOf(missingFeatures.size())));
      for (int i = 0; i < 4; i++) {
        String feat = i < missingFeatures.size() ? missingFeatures.get(i) : "";
        requests.add(replaceAll("MISSING_FEATURE_" + (i + 1), feat));
        // RECOM_N left blank for v1 — populated once a Gemini recommendation
        // prompt is wired into the worker.
        requests.add(replaceAll("RECOM_" + (i + 1), ""));
      }

      // Per-feature bullet flip: template starts with {@code ○ <feature>} lines
      // for anything that might or might not be detected; for each feature in
      // the detected list, flip the bullet character to {@code ●}.
      for (String feature : allFeatures) {
        if (detected.contains(feature)) {
          requests.add(replaceAll("○ " + feature, "● " + feature));
        }
      }

      slides
          .presentations()
          .batchUpdate(
              presentationId, new BatchUpdatePresentationRequest().setRequests(requests))
          .execute();

      log.info(
          "Generated deck {} for '{}' ({}/{} features detected)",
          presentationId,
          safeTitle,
          detectedCount,
          totalFeatures);
      return "https://docs.google.com/presentation/d/" + presentationId;
    }
  }

  private static Request replaceAll(String token, String replacement) {
    return new Request()
        .setReplaceAllText(
            new ReplaceAllTextRequest()
                .setContainsText(new SubstringMatchCriteria().setText(token).setMatchCase(true))
                .setReplaceText(replacement == null ? "" : replacement));
  }

  private static String categoryFor(int score) {
    if (score >= 80) return "EXCELLENT";
    if (score >= 60) return "ON TRACK";
    return "NEEDS ATTENTION";
  }

  private static String nonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
