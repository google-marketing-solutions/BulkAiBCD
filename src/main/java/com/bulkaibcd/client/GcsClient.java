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

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Client gateway responsible for encapsulating all Google Cloud Storage (GCS) operations.
 *
 * <p>Provides clean, decoupled methods for writing JSONL streams, listing blobs, reading blobs,
 * purging temporary batch folders, and auto-garbage collecting uploaded video binaries.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GcsClient {

  private final Storage storage;

  public WriteChannel createWriter(String bucket, String objectPath, String contentType) {
    BlobInfo blobInfo = BlobInfo.newBuilder(bucket, objectPath).setContentType(contentType).build();
    return storage.writer(blobInfo);
  }

  public Iterable<Blob> listBlobs(String bucket, String prefix) {
    return storage.list(bucket, Storage.BlobListOption.prefix(prefix)).iterateAll();
  }

  public void deleteObject(String bucket, String object) {
    try {
      storage.delete(bucket, object);
    } catch (Exception e) {
      log.warn("GcsClient: Failed to delete object gs://{}/{}", bucket, object, e);
    }
  }

  public void deleteBlobs(List<BlobId> blobIds) {
    if (blobIds == null || blobIds.isEmpty()) return;
    try {
      storage.delete(blobIds);
    } catch (Exception e) {
      log.warn("GcsClient: Failed to delete {} blobs", blobIds.size(), e);
    }
  }

  public void deleteGcsObject(String gcsObjectId) {
    if (gcsObjectId == null || gcsObjectId.isBlank()) return;
    int slash = gcsObjectId.indexOf('/');
    if (slash < 0) return;
    String bucket = gcsObjectId.substring(0, slash);
    String object = gcsObjectId.substring(slash + 1);
    deleteObject(bucket, object);
  }

  public void purgeGcsFolder(String bucket, String prefix) {
    try {
      var blobs = storage.list(bucket, Storage.BlobListOption.prefix(prefix));
      List<BlobId> blobIds = new ArrayList<>();
      for (Blob blob : blobs.iterateAll()) {
        blobIds.add(blob.getBlobId());
      }
      if (!blobIds.isEmpty()) {
        storage.delete(blobIds);
        log.info(
            "GcsClient: Auto-GC: Purged {} temporary files in gs://{}/{}",
            blobIds.size(),
            bucket,
            prefix);
      }
    } catch (Exception e) {
      log.warn(
          "GcsClient: Auto-GC: Failed to purge temporary folder gs://{}/{}", bucket, prefix, e);
    }
  }

  public URL signUrl(
      BlobInfo blobInfo, long duration, TimeUnit unit, Storage.SignUrlOption... options) {
    return storage.signUrl(blobInfo, duration, unit, options);
  }
}
