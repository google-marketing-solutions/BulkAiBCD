package com.bulkaibcd.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.gax.paging.Page;
import com.google.cloud.WriteChannel;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GcsClientTest {

  private Storage storage;
  private GcsClient client;

  @BeforeEach
  void setUp() {
    storage = mock(Storage.class);
    client = new GcsClient(storage);
  }

  @Test
  void createWriterCallsStorageWriterWithBlobInfo() {
    WriteChannel channel = mock(WriteChannel.class);
    when(storage.writer(any(BlobInfo.class))).thenReturn(channel);

    WriteChannel result = client.createWriter("my-bucket", "path/file.jsonl", "application/jsonl");
    assertThat(result).isSameAs(channel);

    ArgumentCaptor<BlobInfo> captor = ArgumentCaptor.forClass(BlobInfo.class);
    verify(storage).writer(captor.capture());
    assertThat(captor.getValue().getBucket()).isEqualTo("my-bucket");
    assertThat(captor.getValue().getName()).isEqualTo("path/file.jsonl");
    assertThat(captor.getValue().getContentType()).isEqualTo("application/jsonl");
  }

  @Test
  void listBlobsIteratesOverStorageList() {
    @SuppressWarnings("unchecked")
    Page<Blob> page = mock(Page.class);
    Blob b1 = mock(Blob.class);
    when(page.iterateAll()).thenReturn(List.of(b1));
    when(storage.list(eq("my-bucket"), any(Storage.BlobListOption.class))).thenReturn(page);

    Iterable<Blob> result = client.listBlobs("my-bucket", "prefix/");
    assertThat(result).containsExactly(b1);
  }

  @Test
  void deleteObjectCallsStorageDeleteAndCatchesExceptions() {
    client.deleteObject("my-bucket", "path/file.mp4");
    verify(storage).delete("my-bucket", "path/file.mp4");

    doThrow(new RuntimeException("GCS error")).when(storage).delete("my-bucket", "error.mp4");
    // Should not throw exception
    client.deleteObject("my-bucket", "error.mp4");
  }

  @Test
  void deleteBlobsCallsStorageDeleteListAndCatchesExceptions() {
    BlobId b1 = BlobId.of("bucket", "b1");
    client.deleteBlobs(List.of(b1));
    verify(storage).delete(List.of(b1));

    client.deleteBlobs(null);
    client.deleteBlobs(List.of());

    doThrow(new RuntimeException("GCS error")).when(storage).delete(List.of(b1));
    // Should not throw exception
    client.deleteBlobs(List.of(b1));
  }

  @Test
  void deleteGcsObjectSplitsBucketAndObject() {
    client.deleteGcsObject("my-bucket/folder/video.mp4");
    verify(storage).delete("my-bucket", "folder/video.mp4");

    client.deleteGcsObject(null);
    client.deleteGcsObject("");
    client.deleteGcsObject("no-slash-path");
  }

  @Test
  void purgeGcsFolderDeletesAllBlobsInPrefix() {
    @SuppressWarnings("unchecked")
    Page<Blob> page = mock(Page.class);
    Blob b1 = mock(Blob.class);
    BlobId bid1 = BlobId.of("my-bucket", "batch/1.jsonl");
    when(b1.getBlobId()).thenReturn(bid1);
    when(page.iterateAll()).thenReturn(List.of(b1));
    when(storage.list(eq("my-bucket"), any(Storage.BlobListOption.class))).thenReturn(page);

    client.purgeGcsFolder("my-bucket", "batch/");
    verify(storage).delete(List.of(bid1));
  }

  @Test
  void purgeGcsFolderHandlesExceptionsGracefully() {
    when(storage.list(eq("my-bucket"), any(Storage.BlobListOption.class)))
        .thenThrow(new RuntimeException("List failed"));

    // Should not throw exception
    client.purgeGcsFolder("my-bucket", "batch/");
  }

  @Test
  void createBlobDelegatesToStorage() {
    BlobInfo blobInfo =
        BlobInfo.newBuilder("bucket", "thumbnails/thumb.jpg")
            .setContentType("image/jpeg")
            .build();
    Blob mockBlob = mock(Blob.class);
    byte[] content = new byte[] {1, 2, 3};
    when(storage.create(eq(blobInfo), eq(content))).thenReturn(mockBlob);

    Blob result = client.createBlob("bucket", "thumbnails/thumb.jpg", content, "image/jpeg");
    assertThat(result).isEqualTo(mockBlob);
  }

  @Test
  void signUrlDelegatesToStorage() throws Exception {
    BlobInfo blobInfo = BlobInfo.newBuilder("bucket", "object").build();
    URL expectedUrl = new URL("https://storage.googleapis.com/bucket/object?signed");
    when(storage.signUrl(eq(blobInfo), eq(60L), eq(TimeUnit.MINUTES))).thenReturn(expectedUrl);

    URL result = client.signUrl(blobInfo, 60, TimeUnit.MINUTES);
    assertThat(result).isEqualTo(expectedUrl);
  }
}
