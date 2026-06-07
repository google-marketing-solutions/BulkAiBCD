package com.bulkaibcd.service;

import com.google.api.services.drive.Drive;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class DriveIngestService {

  private final Storage storage;
  private final Drive drive;

  @Value("${app.uploads-bucket}")
  private String bucket;

  // Drive link formats vary — single-account (/drive/folders/), multi-account
  // (/drive/u/0/folders/), Google-corp (/corp/drive/u/0/folders/), plus legacy
  // `?id=` query params. Rather than trying to match every prefix permutation
  // we just look for the ID-bearing path segment anywhere in the URL.
  private static final Pattern[] FILE_ID_PATTERNS = {
      Pattern.compile("/file/d/([A-Za-z0-9_-]+)"),
      Pattern.compile("[?&]id=([A-Za-z0-9_-]+)"),
  };
  private static final Pattern FOLDER_ID_PATTERN =
      Pattern.compile("/folders/([A-Za-z0-9_-]+)");

  /** Lightweight record of a resolved Drive file used to seed individual queue entries. */
  public record ResolvedVideo(
      String fileId,
      String name,
      String mimeType,
      String webViewLink,
      String thumbnailLink) {}

  /**
   * Expands {@code url} to the list of individual video files it represents.
   *
   * <ul>
   *   <li>Folder URL → every video-MIME child (max 100 per call).</li>
   *   <li>Single file URL → a one-element list.</li>
   * </ul>
   *
   * The caller is expected to translate failures into user-facing errors; we
   * just propagate whatever the Drive API says (404 for a typo, 403 for
   * "not shared with the runtime SA", etc.).
   */
  public List<ResolvedVideo> resolve(String url) throws IOException {
    String folderId = parseFolderId(url);
    if (folderId != null) {
      // files.list() silently returns [] when the query references a folder the
      // SA can't see — identical to "the folder is empty". To tell those apart
      // we probe the folder itself first: if this 404s or 403s, the user needs
      // to share it with the SA. If it succeeds, the empty list below really is
      // an empty folder.
      drive.files().get(folderId)
          .setFields("id")
          .setSupportsAllDrives(true)
          .execute();
      String q =
          "'" + folderId + "' in parents and mimeType contains 'video/' and trashed=false";
      com.google.api.services.drive.model.FileList list =
          drive.files().list()
              .setQ(q)
              .setFields("files(id,name,mimeType,webViewLink,thumbnailLink)")
              .setPageSize(100)
              .setSupportsAllDrives(true)
              .setIncludeItemsFromAllDrives(true)
              .execute();
      if (list.getFiles() == null || list.getFiles().isEmpty()) return List.of();
      return list.getFiles().stream().map(DriveIngestService::toResolved).toList();
    }
    String fileId = parseFileId(url);
    if (fileId == null) {
      throw new IllegalArgumentException("Unrecognized Drive URL: " + url);
    }
    com.google.api.services.drive.model.File f =
        drive.files().get(fileId)
            .setFields("id,name,mimeType,webViewLink,thumbnailLink")
            .setSupportsAllDrives(true)
            .execute();
    return List.of(toResolved(f));
  }

  private static ResolvedVideo toResolved(com.google.api.services.drive.model.File f) {
    return new ResolvedVideo(
        f.getId(),
        f.getName(),
        f.getMimeType(),
        f.getWebViewLink(),
        f.getThumbnailLink());
  }

  private static String parseFolderId(String url) {
    if (url == null) return null;
    Matcher m = FOLDER_ID_PATTERN.matcher(url);
    return m.find() ? m.group(1) : null;
  }

  /**
   * Downloads the Drive file referenced by {@code driveUrl} and stages it in the
   * uploads bucket. Returns the resulting {@code bucket/object} path (no gs://
   * prefix) — callers prepend gs:// when passing it to Gemini, matching the
   * convention already used for browser-uploaded files.
   */
  public String ingest(String driveUrl) throws IOException {
    log.info("Service: Starting ingestion for Drive URL: {}", driveUrl);
    String fileId = parseFileId(driveUrl);
    if (fileId == null) {
      throw new IllegalArgumentException("Unrecognized Drive URL: " + driveUrl);
    }
    com.google.api.services.drive.model.File meta =
        drive.files().get(fileId).setFields("id,name,mimeType,size").execute();

    String safeName = safeName(meta.getName());
    String objectName = UUID.randomUUID() + "_" + safeName;
    String contentType = meta.getMimeType() == null ? "video/mp4" : meta.getMimeType();
    BlobInfo blobInfo =
        BlobInfo.newBuilder(bucket, objectName).setContentType(contentType).build();

    try (WriteChannel channel = storage.writer(blobInfo);
        OutputStream os = Channels.newOutputStream(channel)) {
      drive.files().get(fileId).executeMediaAndDownloadTo(os);
    }
    log.info(
        "Ingested Drive file {} ({} bytes) → gs://{}/{}",
        fileId,
        meta.getSize(),
        bucket,
        objectName);
    return bucket + "/" + objectName;
  }

  private static String parseFileId(String url) {
    if (url == null) return null;
    for (Pattern p : FILE_ID_PATTERNS) {
      Matcher m = p.matcher(url);
      if (m.find()) return m.group(1);
    }
    return null;
  }

  private static String safeName(String name) {
    if (name == null || name.isBlank()) return "drive-video.mp4";
    return name.replaceAll("[^A-Za-z0-9._-]", "_");
  }
}
