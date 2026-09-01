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

package com.bulkaibcd.client;

import com.bulkaibcd.config.UserGoogleApiFactory;
import com.bulkaibcd.model.NotDetectedFeatureEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import com.google.api.services.slides.v1.Slides;
import com.google.api.services.slides.v1.model.AffineTransform;
import com.google.api.services.slides.v1.model.BatchUpdatePresentationRequest;
import com.google.api.services.slides.v1.model.DeleteObjectRequest;
import com.google.api.services.slides.v1.model.DeleteTextRequest;
import com.google.api.services.slides.v1.model.Dimension;
import com.google.api.services.slides.v1.model.DuplicateObjectRequest;
import com.google.api.services.slides.v1.model.ImageProperties;
import com.google.api.services.slides.v1.model.InsertTextRequest;
import com.google.api.services.slides.v1.model.Link;
import com.google.api.services.slides.v1.model.OpaqueColor;
import com.google.api.services.slides.v1.model.OptionalColor;
import com.google.api.services.slides.v1.model.Page;
import com.google.api.services.slides.v1.model.PageElement;
import com.google.api.services.slides.v1.model.Presentation;
import com.google.api.services.slides.v1.model.Range;
import com.google.api.services.slides.v1.model.ReplaceAllTextRequest;
import com.google.api.services.slides.v1.model.ReplaceImageRequest;
import com.google.api.services.slides.v1.model.Request;
import com.google.api.services.slides.v1.model.RgbColor;
import com.google.api.services.slides.v1.model.ShapeBackgroundFill;
import com.google.api.services.slides.v1.model.ShapeProperties;
import com.google.api.services.slides.v1.model.SolidFill;
import com.google.api.services.slides.v1.model.SubstringMatchCriteria;
import com.google.api.services.slides.v1.model.TextStyle;
import com.google.api.services.slides.v1.model.UpdateImagePropertiesRequest;
import com.google.api.services.slides.v1.model.UpdatePageElementTransformRequest;
import com.google.api.services.slides.v1.model.UpdateShapePropertiesRequest;
import com.google.api.services.slides.v1.model.UpdateSlidesPositionRequest;
import com.google.api.services.slides.v1.model.UpdateTextStyleRequest;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage.SignUrlOption;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Client gateway responsible for generating bulk Google Slides pitch decks. */
@Component
@Slf4j
public class GoogleSlidesClient {

  private static final String TEMPLATE_CLASSPATH = "/templates/master_pitch_deck.pptx";
  private static final String PPTX_MIME =
      "application/vnd.openxmlformats-officedocument.presentationml.presentation";
  private static final String SLIDES_MIME = "application/vnd.google-apps.presentation";

  private final UserGoogleApiFactory userApis;
  private final GcsClient gcsClient;
  private final String bucket;

  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

  private static final Pattern YOUTUBE_ID_PATTERN =
      Pattern.compile(
          "(?:https?:\\/\\/)?(?:www\\.)?(?:youtube\\.com\\/(?:watch\\?v=|embed\\/|v\\/|shorts\\/)|youtu\\.be\\/)([\\w-]{11})");
  private static final Pattern DRIVE_ID_PATTERN =
      Pattern.compile("/file/d/([A-Za-z0-9_-]+)|[?&]id=([A-Za-z0-9_-]+)");

  private static final double DETAIL_WIDTH_EMU = 3.94 * 914400;
  private static final double DETAIL_HEIGHT_EMU = 2.21 * 914400;
  private static final double SUMMARY_WIDTH_EMU = 1.97 * 914400;
  private static final double SUMMARY_HEIGHT_EMU = 1.11 * 914400;
  private static final String DEFAULT_THUMBNAIL_URL =
      "https://www.gstatic.com/images/icons/material/system/2x/video_library_black_48dp.png";

  private static final RgbColor COLOR_WHITE = createColor(1f, 1f, 1f);
  private static final RgbColor COLOR_DETECTED_GREEN =
      createColor(15f / 255f, 157f / 255f, 88f / 255f);
  private static final RgbColor COLOR_NOT_DETECTED_RED =
      createColor(219f / 255f, 68f / 255f, 55f / 255f);
  private static final RgbColor COLOR_GREY = createColor(117f / 255f, 117f / 255f, 117f / 255f);

  /**
   * Constructs a GoogleSlidesClient with the required API factory, GCS client, and bucket.
   *
   * @param userApis the factory creating user-scoped Google Drive and Slides clients
   * @param gcsClient the client for Google Cloud Storage operations
   * @param bucket the uploads bucket name for caching thumbnails
   */
  @Autowired
  public GoogleSlidesClient(
      UserGoogleApiFactory userApis,
      GcsClient gcsClient,
      @Value("${app.uploads-bucket:}") String bucket) {
    this.userApis = userApis;
    this.gcsClient = gcsClient;
    this.bucket = bucket;
  }

  @Builder
  public record PitchDeckParams(
      String userAccessToken,
      String brandName,
      String marketingObjective,
      String analysisType,
      List<String> allFeatures,
      List<VideoMetadataEntity> videos) {}

  String resolveThumbnailUrl(VideoMetadataEntity video) {
    return doResolveThumbnailUrl(video, null);
  }

  private String resolveThumbnailUrl(
      VideoMetadataEntity video, String userAccessToken, Map<String, String> cache) {
    String cacheKey =
        StringUtils.hasText(video.getVideoId()) ? video.getVideoId() : video.getVideoName();
    if (cacheKey != null && cache.containsKey(cacheKey)) {
      return cache.get(cacheKey);
    }
    String resolved = doResolveThumbnailUrl(video, userAccessToken);
    if (cacheKey != null) {
      cache.put(cacheKey, resolved);
    }
    return resolved;
  }

  private String doResolveThumbnailUrl(VideoMetadataEntity video, String userAccessToken) {
    // 1. YouTube videos have reliable, public, short CDN thumbnail URLs
    if (StringUtils.hasText(video.getVideoUrl())) {
      Matcher matcher = YOUTUBE_ID_PATTERN.matcher(video.getVideoUrl());
      if (matcher.find()) {
        String ytId = matcher.group(1);
        return "https://i.ytimg.com/vi/" + ytId + "/hqdefault.jpg";
      }
    }

    String thumb = video.getThumbnailUrl();

    // 2. Base64 data URI (local file upload) -> decode, upload to GCS, generate signed GET URL
    if (StringUtils.hasText(thumb) && thumb.startsWith("data:")) {
      String signed = uploadBase64ThumbnailToGcs(thumb);
      if (signed != null) {
        return signed;
      }
      return DEFAULT_THUMBNAIL_URL;
    }

    // 3. Drive thumbnail or remote URL (e.g. googleusercontent.com / drive.google.com) ->
    //    Download using userAccessToken, upload to GCS, generate signed GET URL
    if (StringUtils.hasText(thumb)
        && (thumb.contains("googleusercontent.com") || thumb.contains("drive.google.com"))) {
      String signed = downloadAndUploadRemoteThumbnail(thumb, userAccessToken);
      if (signed != null) {
        return signed;
      }
      return DEFAULT_THUMBNAIL_URL;
    }

    // 4. Drive video URL without explicit thumbnail -> extract Drive file ID and fetch thumbnail
    if (!StringUtils.hasText(thumb) && StringUtils.hasText(video.getVideoUrl())) {
      Matcher driveMatcher = DRIVE_ID_PATTERN.matcher(video.getVideoUrl());
      if (driveMatcher.find()) {
        String fileId =
            driveMatcher.group(1) != null ? driveMatcher.group(1) : driveMatcher.group(2);
        String driveThumbUrl = "https://lh3.googleusercontent.com/u/0/d/" + fileId + "=s400";
        String signed = downloadAndUploadRemoteThumbnail(driveThumbUrl, userAccessToken);
        if (signed != null) {
          return signed;
        }
      }
    }

    // 5. Any other existing public HTTP/HTTPS URL within 2048 characters
    if (StringUtils.hasText(thumb)
        && thumb.length() <= 2048
        && (thumb.startsWith("http://") || thumb.startsWith("https://"))) {
      return thumb;
    }

    return DEFAULT_THUMBNAIL_URL;
  }

  private String uploadBase64ThumbnailToGcs(String dataUri) {
    if (!StringUtils.hasText(bucket) || gcsClient == null) {
      log.warn("GoogleSlidesClient: Cannot cache base64 thumbnail; bucket or gcsClient not configured");
      return null;
    }
    try {
      int comma = dataUri.indexOf(',');
      String base64Data = comma >= 0 ? dataUri.substring(comma + 1) : dataUri;
      byte[] bytes = Base64.getDecoder().decode(base64Data.trim());
      String objectName = "thumbnails/" + UUID.randomUUID() + ".jpg";
      gcsClient.createBlob(bucket, objectName, bytes, "image/jpeg");
      BlobInfo blobInfo = BlobInfo.newBuilder(bucket, objectName).build();
      URL signed =
          gcsClient.signUrl(
              blobInfo,
              60,
              TimeUnit.MINUTES,
              SignUrlOption.httpMethod(HttpMethod.GET),
              SignUrlOption.withV4Signature());
      log.info("GoogleSlidesClient: Generated on-the-fly signed thumbnail URL for uploaded video");
      return signed.toString();
    } catch (Exception e) {
      log.warn("GoogleSlidesClient: Failed to upload base64 thumbnail to GCS: {}", e.getMessage());
      return null;
    }
  }

  private String downloadAndUploadRemoteThumbnail(String remoteUrl, String userAccessToken) {
    if (!StringUtils.hasText(bucket) || gcsClient == null) {
      log.warn("GoogleSlidesClient: Cannot cache remote thumbnail; bucket or gcsClient not configured");
      return null;
    }
    try {
      byte[] bytes = fetchRemoteImageBytes(remoteUrl, userAccessToken);
      if (bytes == null || bytes.length == 0) {
        log.warn("GoogleSlidesClient: No bytes returned for remote thumbnail {}", remoteUrl);
        return null;
      }
      String objectName = "thumbnails/" + UUID.randomUUID() + ".jpg";
      gcsClient.createBlob(bucket, objectName, bytes, "image/jpeg");
      BlobInfo blobInfo = BlobInfo.newBuilder(bucket, objectName).build();
      URL signed =
          gcsClient.signUrl(
              blobInfo,
              60,
              TimeUnit.MINUTES,
              SignUrlOption.httpMethod(HttpMethod.GET),
              SignUrlOption.withV4Signature());
      log.info("GoogleSlidesClient: Successfully fetched Drive thumbnail, cached in GCS, and signed URL");
      return signed.toString();
    } catch (Exception e) {
      log.warn("GoogleSlidesClient: Failed to download and cache remote thumbnail: {}", e.getMessage());
      return null;
    }
  }

  private byte[] fetchRemoteImageBytes(String remoteUrl, String userAccessToken) throws IOException {
    String currentUrl = remoteUrl;
    for (int i = 0; i < 3; i++) {
      HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(5000);
      conn.setReadTimeout(5000);
      conn.setInstanceFollowRedirects(false);
      if (StringUtils.hasText(userAccessToken)) {
        conn.setRequestProperty("Authorization", "Bearer " + userAccessToken);
      }
      int code = conn.getResponseCode();
      if (code == HttpURLConnection.HTTP_OK) {
        try (InputStream in = conn.getInputStream()) {
          return in.readAllBytes();
        }
      } else if (code == HttpURLConnection.HTTP_MOVED_TEMP
          || code == HttpURLConnection.HTTP_MOVED_PERM
          || code == HttpURLConnection.HTTP_SEE_OTHER
          || code == 307
          || code == 308) {
        String location = conn.getHeaderField("Location");
        if (StringUtils.hasText(location)) {
          currentUrl = location;
          continue;
        }
      }
      log.warn("GoogleSlidesClient: HTTP {} when fetching remote image {}", code, currentUrl);
      return null;
    }
    return null;
  }

  /**
   * Generates a bulk Google Slides pitch deck presentation populated with analysis findings.
   *
   * @param p the parameters required to build the pitch deck
   * @return the URL of the generated Google Slides presentation
   * @throws IOException if Google Drive or Slides API operations fail
   */
  public String generateBulkPitchDeck(PitchDeckParams p) throws IOException {
    String userAccessToken = p.userAccessToken();
    String brandName = p.brandName();
    String marketingObjective = p.marketingObjective();
    String analysisType = p.analysisType();
    List<String> allFeatures = p.allFeatures();
    List<VideoMetadataEntity> videos = p.videos();

    Drive drive = userApis.drive(userAccessToken);
    Slides slides = userApis.slides(userAccessToken);

    String safeBrand = StringUtils.hasText(brandName) ? brandName : "Untitled";
    String presentationTitle = "Bulk AiBCD report - " + safeBrand;

    String presentationId;
    try (InputStream templateStream = getClass().getResourceAsStream(TEMPLATE_CLASSPATH)) {
      if (templateStream == null) {
        throw new IOException("Template not found at " + TEMPLATE_CLASSPATH);
      }
      File fileMetadata = new File().setName(presentationTitle).setMimeType(SLIDES_MIME);
      InputStreamContent mediaContent = new InputStreamContent(PPTX_MIME, templateStream);
      File uploadedDeck =
          drive.files().create(fileMetadata, mediaContent).setFields("id").execute();
      presentationId = uploadedDeck.getId();
    }

    if (videos == null || videos.isEmpty()) {
      return makePublicAndGetUrl(drive, presentationId);
    }

    Map<String, String> thumbnailCache = new HashMap<>();

    List<List<NotDetectedFeatureEntity>> missingFeaturesPerVideo = new ArrayList<>();
    int[] missingSlidesCount = new int[videos.size()];
    int[] totalMissingSlidesBefore = new int[videos.size()];
    int currentTotal = 0;

    for (int i = 0; i < videos.size(); i++) {
      VideoMetadataEntity v = videos.get(i);
      List<NotDetectedFeatureEntity> missing =
          v.getNotDetectedFeatures() != null ? v.getNotDetectedFeatures() : Collections.emptyList();
      missingFeaturesPerVideo.add(missing);
      int needed = (missing.size() + 3) / 4;
      missingSlidesCount[i] = needed;
      totalMissingSlidesBefore[i] = currentTotal;
      currentTotal += needed;
    }
    int totalMissingSlidesCount = currentTotal;

    Presentation presentation = slides.presentations().get(presentationId).execute();
    List<Page> initialSlides = presentation.getSlides();

    String summarySlideId = initialSlides.get(2).getObjectId();
    String removingSlideId;
    String analysisSlideID;
    String missingFeaturesSlideId = initialSlides.get(5).getObjectId();

    if ("light".equals(analysisType)) {
      analysisSlideID = initialSlides.get(3).getObjectId();
      removingSlideId = initialSlides.get(4).getObjectId();
    } else {
      analysisSlideID = initialSlides.get(4).getObjectId();
      removingSlideId = initialSlides.get(3).getObjectId();
    }

    int videosCount = videos.size();
    int summarySlidesCount = (videosCount + 2) / 3;

    List<Request> batchRequests = new ArrayList<>();

    batchRequests.add(
        new Request().setDeleteObject(new DeleteObjectRequest().setObjectId(removingSlideId)));

    for (int i = 0; i < summarySlidesCount - 1; i++) {
      batchRequests.add(
          new Request()
              .setDuplicateObject(new DuplicateObjectRequest().setObjectId(summarySlideId)));
    }
    for (int i = 0; i < videosCount - 1; i++) {
      batchRequests.add(
          new Request()
              .setDuplicateObject(new DuplicateObjectRequest().setObjectId(analysisSlideID)));
    }
    if (totalMissingSlidesCount > 0) {
      for (int i = 0; i < totalMissingSlidesCount - 1; i++) {
        batchRequests.add(
            new Request()
                .setDuplicateObject(
                    new DuplicateObjectRequest().setObjectId(missingFeaturesSlideId)));
      }
    } else {
      batchRequests.add(
          new Request()
              .setDeleteObject(new DeleteObjectRequest().setObjectId(missingFeaturesSlideId)));
    }

    if (!batchRequests.isEmpty()) {
      slides
          .presentations()
          .batchUpdate(
              presentationId, new BatchUpdatePresentationRequest().setRequests(batchRequests))
          .execute();
      presentation = slides.presentations().get(presentationId).execute();
    }

    List<Page> finalSlides = presentation.getSlides();
    batchRequests.clear();

    String reportDetailsSlideId = finalSlides.get(1).getObjectId();
    batchRequests.add(replaceAllTextOnSlide("BRAND_NAME", safeBrand, reportDetailsSlideId));
    batchRequests.add(
        replaceAllTextOnSlide("VIDEO_COUNT", String.valueOf(videosCount), reportDetailsSlideId));
    batchRequests.add(
        replaceAllTextOnSlide(
            "COMPLETION_DATE", LocalDate.now().format(DATE_FORMATTER), reportDetailsSlideId));
    batchRequests.add(
        replaceAllTextOnSlide(
            "MARKETING_OBJECTIVE",
            StringUtils.hasText(marketingObjective) ? marketingObjective : "CORE",
            reportDetailsSlideId));

    for (int i = 0; i < summarySlidesCount; i++) {
      Page summarySlide = finalSlides.get(2 + i);
      String slideId = summarySlide.getObjectId();

      for (int j = 0; j < 3; j++) {
        int videoIdx = i * 3 + j;
        String titlePlaceholder = "VIDEO_TITLE_" + (j + 1);
        String adherencePlaceholder = "ADHERENCE_CATEGORY_" + (j + 1);
        String thumbnailPlaceholder = "THUMBNAIL_" + (j + 1);

        if (videoIdx < videosCount) {
          VideoMetadataEntity video = videos.get(videoIdx);
          int score = calculateAverageScore(video);

          batchRequests.add(replaceAllTextOnSlide(titlePlaceholder, video.getVideoName(), slideId));
          batchRequests.add(
              replaceAllTextOnSlide(adherencePlaceholder, categoryFor(score), slideId));

          getPageElementByDescription(summarySlide, adherencePlaceholder)
              .ifPresent(
                  el -> {
                    batchRequests.add(updateShapeColor(el.getObjectId(), colorForCategory(score)));
                    batchRequests.add(updateTextColor(el.getObjectId(), COLOR_WHITE));
                  });

          getPageElementByDescription(summarySlide, thumbnailPlaceholder)
              .ifPresent(
                  el -> {
                    String thumbUrl = resolveThumbnailUrl(video, userAccessToken, thumbnailCache);
                    batchRequests.add(resizeImage(el, SUMMARY_WIDTH_EMU, SUMMARY_HEIGHT_EMU));
                    batchRequests.add(replaceImage(el.getObjectId(), thumbUrl));
                    if (StringUtils.hasText(video.getVideoUrl())) {
                      batchRequests.add(addLinkToElement(el, video.getVideoUrl()));
                    }
                  });
        } else {
          getPageElementByDescription(summarySlide, "ROW_" + (j + 1))
              .ifPresent(el -> batchRequests.add(deleteObject(el.getObjectId())));
          getPageElementByDescription(summarySlide, titlePlaceholder)
              .ifPresent(el -> batchRequests.add(deleteObject(el.getObjectId())));
          getPageElementByDescription(summarySlide, adherencePlaceholder)
              .ifPresent(el -> batchRequests.add(deleteObject(el.getObjectId())));
          getPageElementByDescription(summarySlide, thumbnailPlaceholder)
              .ifPresent(el -> batchRequests.add(deleteObject(el.getObjectId())));
        }
      }
    }

    int detailSlideStartIndex = 2 + summarySlidesCount;
    for (int i = 0; i < videosCount; i++) {
      Page detailSlide = finalSlides.get(detailSlideStartIndex + i);
      String slideId = detailSlide.getObjectId();
      VideoMetadataEntity video = videos.get(i);

      Set<String> detected =
          video.getFeatures() != null ? new HashSet<>(video.getFeatures()) : Collections.emptySet();
      Set<String> notRelevant =
          video.getNotDetected() != null
              ? new HashSet<>(video.getNotDetected())
              : Collections.emptySet();
      Set<String> relevant =
          video.getRelevantFeatures() != null
              ? new HashSet<>(video.getRelevantFeatures())
              : Collections.emptySet();
      int score = calculateAverageScore(video);
      String category = categoryFor(score);

      batchRequests.add(replaceAllTextOnSlide("ASSET_NUMBER", String.valueOf(i + 1), slideId));
      batchRequests.add(replaceAllTextOnSlide("VIDEO_COUNT", String.valueOf(videosCount), slideId));
      batchRequests.add(replaceAllTextOnSlide("ASSET_NAME", video.getVideoName(), slideId));

      String displayLink =
          video.getVideoUrl() != null && video.getVideoUrl().length() > 80
              ? video.getVideoUrl().substring(0, 77) + "..."
              : video.getVideoUrl();
      batchRequests.add(
          replaceAllTextOnSlide("ASSET_LINK", displayLink != null ? displayLink : "", slideId));
      batchRequests.add(replaceAllTextOnSlide("ADHERENCE_PERC", score + "%", slideId));
      batchRequests.add(replaceAllTextOnSlide("ASSET_RESULT", category, slideId));
      batchRequests.add(replaceAllTextOnSlide("ADHERENCE_CATEGORY", category, slideId));
      batchRequests.add(
          replaceAllTextOnSlide("ADHERENCE_X", String.valueOf(detected.size()), slideId));
      batchRequests.add(
          replaceAllTextOnSlide("ADHERENCE_Y", String.valueOf(allFeatures.size()), slideId));

      getPageElementByDescription(detailSlide, "ADHERENCE_CATEGORY")
          .ifPresent(
              el -> {
                batchRequests.add(updateShapeColor(el.getObjectId(), colorForCategory(score)));
                batchRequests.add(updateTextColor(el.getObjectId(), COLOR_WHITE));
              });

      getPageElementByDescription(detailSlide, "THUMBNAIL")
          .ifPresent(
              el -> {
                String thumbUrl = resolveThumbnailUrl(video, userAccessToken, thumbnailCache);
                batchRequests.add(resizeImage(el, DETAIL_WIDTH_EMU, DETAIL_HEIGHT_EMU));
                batchRequests.add(replaceImage(el.getObjectId(), thumbUrl));
                if (StringUtils.hasText(video.getVideoUrl())) {
                  batchRequests.add(addLinkToElement(el, video.getVideoUrl()));
                }
              });

      for (String feature : allFeatures) {
        getPageElementByTextMatch(detailSlide, feature)
            .ifPresent(
                el -> {
                  boolean isDetected = detected.contains(feature);
                  String bullet = isDetected ? "●" : "○";
                  RgbColor color = COLOR_GREY;
                  if (relevant.contains(feature)) color = COLOR_DETECTED_GREEN;
                  if (notRelevant.contains(feature)) color = COLOR_NOT_DETECTED_RED;

                  String objectId = el.getObjectId();

                  batchRequests.add(
                      new Request()
                          .setDeleteText(
                              new DeleteTextRequest()
                                  .setObjectId(objectId)
                                  .setTextRange(
                                      new Range()
                                          .setType("FIXED_RANGE")
                                          .setStartIndex(0)
                                          .setEndIndex(1))));
                  batchRequests.add(
                      new Request()
                          .setInsertText(
                              new InsertTextRequest()
                                  .setObjectId(objectId)
                                  .setInsertionIndex(0)
                                  .setText(bullet)));
                  batchRequests.add(
                      new Request()
                          .setUpdateTextStyle(
                              new UpdateTextStyleRequest()
                                  .setObjectId(objectId)
                                  .setTextRange(new Range().setType("ALL"))
                                  .setStyle(
                                      new TextStyle()
                                          .setForegroundColor(
                                              new OptionalColor()
                                                  .setOpaqueColor(
                                                      new OpaqueColor().setRgbColor(color)))
                                          .setBold(true))
                                  .setFields("foregroundColor,bold")));
                });
      }
    }

    int missingSlideStartIndex = 2 + summarySlidesCount + videosCount;
    int missingSlideCursor = 0;
    List<Request> reorderRequests = new ArrayList<>();

    for (int i = 0; i < videosCount; i++) {
      VideoMetadataEntity video = videos.get(i);
      List<NotDetectedFeatureEntity> missing = missingFeaturesPerVideo.get(i);
      int currentVideoMissingSlides = missingSlidesCount[i];
      List<String> missingSlideIds = new ArrayList<>();

      for (int pageNum = 0; pageNum < currentVideoMissingSlides; pageNum++) {
        Page missingSlide = finalSlides.get(missingSlideStartIndex + missingSlideCursor);
        String slideId = missingSlide.getObjectId();
        missingSlideIds.add(slideId);
        missingSlideCursor++;

        int score = calculateAverageScore(video);
        batchRequests.add(replaceAllTextOnSlide("ASSET_NUMBER", String.valueOf(i + 1), slideId));
        batchRequests.add(
            replaceAllTextOnSlide("VIDEO_COUNT", String.valueOf(videosCount), slideId));
        batchRequests.add(replaceAllTextOnSlide("ASSET_NAME", video.getVideoName(), slideId));
        batchRequests.add(
            replaceAllTextOnSlide(
                "MARKETING_OBJECTIVE",
                StringUtils.hasText(marketingObjective) ? marketingObjective : "CORE",
                slideId));
        batchRequests.add(replaceAllTextOnSlide("ADHERENCE_PERC", score + "%", slideId));
        batchRequests.add(replaceAllTextOnSlide("ASSET_RESULT", categoryFor(score), slideId));
        batchRequests.add(replaceAllTextOnSlide("ADHERENCE_CATEGORY", categoryFor(score), slideId));
        batchRequests.add(
            replaceAllTextOnSlide(
                "MISSING_FEATURES_COUNT", String.valueOf(missing.size()), slideId));

        int startIdx = pageNum * 4;
        for (int j = 0; j < 4; j++) {
          String featPlaceholder = "MISSING_FEATURE_" + (j + 1);
          String recomPlaceholder = "RECOM_" + (j + 1);

          if (startIdx + j < missing.size()) {
            String feature = missing.get(startIdx + j).getFeature();
            String rationle = missing.get(startIdx + j).getRationale();
            batchRequests.add(replaceAllTextOnSlide(featPlaceholder, feature, slideId));
            batchRequests.add(replaceAllTextOnSlide(recomPlaceholder, rationle, slideId));
          } else {
            getPageElementByDescription(missingSlide, featPlaceholder)
                .ifPresent(el -> batchRequests.add(deleteObject(el.getObjectId())));
            getPageElementByDescription(missingSlide, recomPlaceholder)
                .ifPresent(el -> batchRequests.add(deleteObject(el.getObjectId())));
          }
        }
      }

      if (!missingSlideIds.isEmpty()) {
        int insertionIndex = (detailSlideStartIndex + i + 1) + totalMissingSlidesBefore[i];
        reorderRequests.add(
            new Request()
                .setUpdateSlidesPosition(
                    new UpdateSlidesPositionRequest()
                        .setSlideObjectIds(missingSlideIds)
                        .setInsertionIndex(insertionIndex)));
      }
    }

    batchRequests.addAll(reorderRequests);

    if (!batchRequests.isEmpty()) {
      slides
          .presentations()
          .batchUpdate(
              presentationId, new BatchUpdatePresentationRequest().setRequests(batchRequests))
          .execute();
    }

    log.info(
        "GoogleSlidesClient: Successfully generated bulk deck {} for brand '{}'",
        presentationId,
        safeBrand);
    return makePublicAndGetUrl(drive, presentationId);
  }

  private String makePublicAndGetUrl(Drive drive, String presentationId) throws IOException {
    try {
      drive
          .permissions()
          .create(presentationId, new Permission().setType("anyone").setRole("reader"))
          .execute();
    } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
      log.warn(
          "GoogleSlidesClient: Could not make presentation public due to org policy ({}). Returning"
              + " private URL.",
          e.getDetails() != null ? e.getDetails().getMessage() : e.getMessage());
    }
    return "https://docs.google.com/presentation/d/" + presentationId;
  }

  private int calculateAverageScore(VideoMetadataEntity v) {
    int total = 0, count = 0;
    if (v.getAScore() != null) {
      total += v.getAScore();
      count++;
    }
    if (v.getBScore() != null) {
      total += v.getBScore();
      count++;
    }
    if (v.getCScore() != null) {
      total += v.getCScore();
      count++;
    }
    if (v.getDScore() != null) {
      total += v.getDScore();
      count++;
    }
    return count == 0 ? 0 : total / count;
  }

  private static String categoryFor(int score) {
    if (score >= 80) return "EXCELLENT";
    if (score >= 60) return "ON TRACK";
    return "NEEDS ATTENTION";
  }

  private static RgbColor colorForCategory(int score) {
    if (score >= 80) return createColor(0f, 176f / 255f, 80f / 255f);
    if (score >= 60) return createColor(1f, 192f / 255f, 0f);
    return createColor(1f, 0f, 0f);
  }

  private static RgbColor createColor(float r, float g, float b) {
    return new RgbColor().setRed(r).setGreen(g).setBlue(b);
  }

  private Request replaceAllTextOnSlide(String token, String value, String slideId) {
    return new Request()
        .setReplaceAllText(
            new ReplaceAllTextRequest()
                .setContainsText(new SubstringMatchCriteria().setText(token).setMatchCase(true))
                .setReplaceText(value == null ? "" : value)
                .setPageObjectIds(List.of(slideId)));
  }

  private Request updateShapeColor(String objectId, RgbColor color) {
    return new Request()
        .setUpdateShapeProperties(
            new UpdateShapePropertiesRequest()
                .setObjectId(objectId)
                .setShapeProperties(
                    new ShapeProperties()
                        .setShapeBackgroundFill(
                            new ShapeBackgroundFill()
                                .setSolidFill(
                                    new SolidFill()
                                        .setColor(new OpaqueColor().setRgbColor(color)))))
                .setFields("shapeBackgroundFill.solidFill.color"));
  }

  private Request updateTextColor(String objectId, RgbColor color) {
    return new Request()
        .setUpdateTextStyle(
            new UpdateTextStyleRequest()
                .setObjectId(objectId)
                .setTextRange(new Range().setType("ALL"))
                .setStyle(
                    new TextStyle()
                        .setForegroundColor(
                            new OptionalColor()
                                .setOpaqueColor(new OpaqueColor().setRgbColor(color))))
                .setFields("foregroundColor"));
  }

  private Request replaceImage(String objectId, String url) {
    return new Request()
        .setReplaceImage(
            new ReplaceImageRequest()
                .setImageObjectId(objectId)
                .setUrl(url)
                .setImageReplaceMethod("CENTER_INSIDE"));
  }

  private Request deleteObject(String objectId) {
    return new Request().setDeleteObject(new DeleteObjectRequest().setObjectId(objectId));
  }

  private Request resizeImage(PageElement element, double targetWidth, double targetHeight) {
    double widthEmu = toEmu(element.getSize().getWidth());
    double heightEmu = toEmu(element.getSize().getHeight());
    double scaleX = element.getTransform().getScaleX();
    double scaleY = element.getTransform().getScaleY();

    if (widthEmu > 0) scaleX = targetWidth / widthEmu;
    if (heightEmu > 0) scaleY = targetHeight / heightEmu;

    AffineTransform newTransform =
        element.getTransform().clone().setScaleX(scaleX).setScaleY(scaleY);
    return new Request()
        .setUpdatePageElementTransform(
            new UpdatePageElementTransformRequest()
                .setObjectId(element.getObjectId())
                .setTransform(newTransform)
                .setApplyMode("ABSOLUTE"));
  }

  private Request addLinkToElement(PageElement element, String url) {
    Link link = new Link().setUrl(url);
    if (element.getImage() != null) {
      return new Request()
          .setUpdateImageProperties(
              new UpdateImagePropertiesRequest()
                  .setObjectId(element.getObjectId())
                  .setImageProperties(new ImageProperties().setLink(link))
                  .setFields("link"));
    } else {
      return new Request()
          .setUpdateShapeProperties(
              new UpdateShapePropertiesRequest()
                  .setObjectId(element.getObjectId())
                  .setShapeProperties(new ShapeProperties().setLink(link))
                  .setFields("link"));
    }
  }

  private double toEmu(Dimension dimension) {
    if ("PT".equals(dimension.getUnit())) return dimension.getMagnitude() * 12700;
    return dimension.getMagnitude();
  }

  private Optional<PageElement> getPageElementByDescription(Page page, String desc) {
    if (page.getPageElements() == null) return Optional.empty();
    return page.getPageElements().stream()
        .filter(el -> desc.equals(el.getDescription()))
        .findFirst();
  }

  private Optional<PageElement> getPageElementByTextMatch(Page page, String textMatch) {
    if (page.getPageElements() == null) return Optional.empty();
    return page.getPageElements().stream()
        .filter(
            el -> {
              if (el.getShape() != null
                  && el.getShape().getText() != null
                  && el.getShape().getText().getTextElements() != null) {
                String text =
                    el.getShape().getText().getTextElements().stream()
                        .filter(te -> te.getTextRun() != null)
                        .map(te -> te.getTextRun().getContent())
                        .collect(Collectors.joining())
                        .trim();
                return text.startsWith("○ " + textMatch) || text.startsWith("● " + textMatch);
              }
              return false;
            })
        .findFirst();
  }
}
