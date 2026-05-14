package com.bulkaibcd.service;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.SignUrlOption;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class UploadUrlService {

  private final Storage storage;

  @Value("${app.uploads-bucket}")
  private String bucket;

  public record SignedUploadUrl(String url, String gcsObjectId) {}

  /** Returns a V4 signed PUT URL good for 15 minutes plus the GCS path the client should report back. */
  public SignedUploadUrl create(String filename, String contentType) {
    String safe = (filename == null || filename.isBlank()) ? "video.mp4" : filename;
    String objectName = UUID.randomUUID() + "_" + safe.replaceAll("[^A-Za-z0-9._-]", "_");
    BlobInfo blobInfo =
        BlobInfo.newBuilder(bucket, objectName)
            .setContentType(contentType == null ? "application/octet-stream" : contentType)
            .build();

    URL signed =
        storage.signUrl(
            blobInfo,
            15,
            TimeUnit.MINUTES,
            SignUrlOption.httpMethod(HttpMethod.PUT),
            SignUrlOption.withExtHeaders(
                Map.of("Content-Type", contentType == null ? "application/octet-stream" : contentType)),
            SignUrlOption.withV4Signature());

    return new SignedUploadUrl(signed.toString(), bucket + "/" + objectName);
  }

  /** Best-effort delete of an uploaded blob; logs and swallows errors. */
  public void delete(String gcsObjectId) {
    if (gcsObjectId == null || gcsObjectId.isBlank()) return;
    int slash = gcsObjectId.indexOf('/');
    if (slash < 0) {
      log.warn("Bad gcsObjectId (no slash): {}", gcsObjectId);
      return;
    }
    String b = gcsObjectId.substring(0, slash);
    String o = gcsObjectId.substring(slash + 1);
    try {
      boolean deleted = storage.delete(b, o);
      log.info("GCS delete {}/{} -> {}", b, o, deleted);
    } catch (Exception e) {
      log.warn("GCS delete failed for {}", gcsObjectId, e);
    }
  }
}
