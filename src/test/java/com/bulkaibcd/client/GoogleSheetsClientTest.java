package com.bulkaibcd.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.config.UserGoogleApiFactory;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.FeatureParameter;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.FeatureConfigService;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class GoogleSheetsClientTest {

  @BeforeAll
  static void beforeAll() {
    System.setProperty("java.awt.headless", "true");
  }

  private UserGoogleApiFactory userApis;
  private AnalysisRequestRepository analysisRepo;
  private VideoMetadataRepository videoMetadataRepo;
  private FeatureConfigService featureConfigService;
  private GoogleSheetsClient client;

  @BeforeEach
  void setUp() {
    System.setProperty("java.awt.headless", "true");
    userApis = mock(UserGoogleApiFactory.class);
    analysisRepo = mock(AnalysisRequestRepository.class);
    videoMetadataRepo = mock(VideoMetadataRepository.class);
    featureConfigService = mock(FeatureConfigService.class);
    client = new GoogleSheetsClient(userApis, analysisRepo, videoMetadataRepo, featureConfigService);
  }

  @Test
  void generateXlsxBytesSucceeds() throws Exception {
    AnalysisRequestEntity analysis =
        AnalysisRequestEntity.builder()
            .analysisId("ana-1")
            .analysisType("standard")
            .brandName("Google")
            .build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(analysis));

    FeatureParameter fp1 = new FeatureParameter();
    fp1.setId("a_first_5_secs");
    fp1.setName("First 5 Secs");

    when(featureConfigService.getFeaturesByType("standard")).thenReturn(List.of(fp1));

    VideoMetadataEntity v1 =
        VideoMetadataEntity.builder()
            .id("ana-1_v1")
            .analysisId("ana-1")
            .videoId("v1")
            .brand("Google")
            .assetName("Ad 1")
            .vertical("Tech")
            .sourceType("YOUTUBE")
            .videoLanguage("en")
            .videoUrl("https://youtube.com/1")
            .product("Pixel")
            .recommendations("None")
            .features(List.of("First 5 Secs"))
            .build();

    when(videoMetadataRepo.findByAnalysisId("ana-1")).thenReturn(Flux.just(v1));

    byte[] bytes = client.generateXlsxBytes("ana-1");
    assertThat(bytes).isNotEmpty();

    // Verify it is a valid XSSFWorkbook
    try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      assertThat(wb.getNumberOfSheets()).isEqualTo(1);
      assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("ABCD Analysis");
      assertThat(wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Brand");
      assertThat(wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("Google");
    }
  }

  @Test
  void generateXlsxBytesThrowsWhenAnalysisNotFound() {
    when(analysisRepo.findById("missing")).thenReturn(Mono.empty());

    assertThatThrownBy(() -> client.generateXlsxBytes("missing"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Analysis not found");
  }

  @Test
  void generateSheetInUserDriveUploadsAndReturnsSheetUrl() throws Exception {
    AnalysisRequestEntity analysis =
        AnalysisRequestEntity.builder()
            .analysisId("ana-1")
            .analysisType("standard")
            .brandName("Google")
            .build();
    when(analysisRepo.findById("ana-1")).thenReturn(Mono.just(analysis));
    when(featureConfigService.getFeaturesByType("standard")).thenReturn(List.of());
    when(videoMetadataRepo.findByAnalysisId("ana-1")).thenReturn(Flux.empty());

    Drive drive = mock(Drive.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(userApis.drive("user-token")).thenReturn(drive);

    File uploadedFile = new File().setId("sheet-doc-id-123");
    when(drive.files().create(any(File.class), any()).setFields("id").execute())
        .thenReturn(uploadedFile);

    String result = client.generateSheetInUserDrive("ana-1", "user-token");
    assertThat(result).isEqualTo("https://docs.google.com/spreadsheets/d/sheet-doc-id-123");
  }
}
