package com.bulkaibcd.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.cloud.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GoogleDriveClientTest {

  private Storage storage;
  private Drive drive;
  private GoogleDriveClient client;

  @BeforeEach
  void setUp() {
    storage = mock(Storage.class);
    drive = mock(Drive.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    client = new GoogleDriveClient(storage, drive);
    ReflectionTestUtils.setField(client, "bucket", "test-bucket");
  }

  @Test
  void resolveSingleFileByIdPattern() throws Exception {
    String url = "https://drive.google.com/file/d/1a2b3c4d5e/view";
    File mockFile = new File();
    mockFile.setId("1a2b3c4d5e");
    mockFile.setName("test-video.mp4");
    mockFile.setMimeType("video/mp4");
    mockFile.setWebViewLink("https://drive.google.com/file/d/1a2b3c4d5e/view");
    mockFile.setThumbnailLink("https://thumb.url");

    when(drive.files().get("1a2b3c4d5e").setFields(anyString()).setSupportsAllDrives(true).execute())
        .thenReturn(mockFile);

    List<GoogleDriveClient.ResolvedVideo> result = client.resolve(url);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).fileId()).isEqualTo("1a2b3c4d5e");
    assertThat(result.get(0).name()).isEqualTo("test-video.mp4");
    assertThat(result.get(0).mimeType()).isEqualTo("video/mp4");
  }

  @Test
  void resolveFolderUrl() throws Exception {
    String url = "https://drive.google.com/drive/folders/folder-123";
    File folderMeta = new File().setId("folder-123");
    when(drive.files().get("folder-123").setFields(anyString()).setSupportsAllDrives(true).execute())
        .thenReturn(folderMeta);

    File video1 = new File().setId("v1").setName("vid1.mp4").setMimeType("video/mp4");
    File video2 = new File().setId("v2").setName("vid2.mp4").setMimeType("video/mp4");
    FileList fileList = new FileList().setFiles(List.of(video1, video2));

    when(drive.files().list().setQ(anyString()).setFields(anyString()).setPageSize(100).setSupportsAllDrives(true).setIncludeItemsFromAllDrives(true).execute())
        .thenReturn(fileList);

    List<GoogleDriveClient.ResolvedVideo> result = client.resolve(url);
    assertThat(result).hasSize(2);
    assertThat(result.get(0).fileId()).isEqualTo("v1");
    assertThat(result.get(1).fileId()).isEqualTo("v2");
  }

  @Test
  void resolveFolderWithNoFilesReturnsEmptyList() throws Exception {
    String url = "https://drive.google.com/drive/folders/empty-folder";
    File folderMeta = new File().setId("empty-folder");
    when(drive.files().get("empty-folder").setFields(anyString()).setSupportsAllDrives(true).execute())
        .thenReturn(folderMeta);

    FileList fileList = new FileList().setFiles(null);
    when(drive.files().list().setQ(anyString()).setFields(anyString()).setPageSize(100).setSupportsAllDrives(true).setIncludeItemsFromAllDrives(true).execute())
        .thenReturn(fileList);

    List<GoogleDriveClient.ResolvedVideo> result = client.resolve(url);
    assertThat(result).isEmpty();
  }

  @Test
  void resolveUnrecognizedUrlThrowsException() {
    assertThatThrownBy(() -> client.resolve("https://example.com/not-drive"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unrecognized Drive URL");
  }

  @Test
  void ingestUnrecognizedUrlThrowsException() {
    assertThatThrownBy(() -> client.ingest("https://invalid.com/file"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unrecognized Drive URL");
  }
}
