package com.bulkaibcd.client;

import com.bulkaibcd.config.UserGoogleApiFactory;
import com.bulkaibcd.enums.SourceType;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.FeatureParameter;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.FeatureConfigService;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/** Client gateway responsible for generating Google Sheets reports in the user's Drive. */
@Component
@Slf4j
@RequiredArgsConstructor
public class GoogleSheetsClient {

  private static final String[] HEADERS = {
    "Brand",
    "Asset Name",
    "Vertical",
    "Source Type",
    "Language",
    "Video URL",
    "Audio All",
    "Text All",
    "Product(s) identified by AI",
    "Recommendations",
  };

  private static final byte[] RED_FILL_RGB = new byte[] {(byte) 250, (byte) 210, (byte) 207};

  private static final String XLSX_MIME =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final String GOOGLE_SHEET_MIME = "application/vnd.google-apps.spreadsheet";

  private final UserGoogleApiFactory userApis;
  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoMetadataRepository videoMetadataRepository;
  private final FeatureConfigService featureConfigService;

  public byte[] generateXlsxBytes(String analysisId) throws IOException {
    AnalysisRequestEntity analysis = analysisRequestRepository.findById(analysisId).block();
    if (analysis == null) throw new IOException("Analysis not found: " + analysisId);
    String analysisType =
        analysis.getAnalysisType() == null ? "standard" : analysis.getAnalysisType();

    List<VideoMetadataEntity> videos =
        videoMetadataRepository.findByAnalysisId(analysisId).collectList().block();
    if (videos == null) videos = List.of();

    try (XSSFWorkbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      XSSFSheet sheet = wb.createSheet("ABCD Analysis");

      CellStyle header = wb.createCellStyle();
      Font headerFont = wb.createFont();
      headerFont.setBold(true);
      headerFont.setColor(IndexedColors.WHITE.getIndex());
      header.setFont(headerFont);
      XSSFColor headerBg = new XSSFColor(new byte[] {(byte) 26, (byte) 115, (byte) 232}, null);
      header.setFillForegroundColor(headerBg);
      header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
      header.setBorderBottom(BorderStyle.THIN);

      CellStyle redFill = wb.createCellStyle();
      redFill.setFillForegroundColor(new XSSFColor(RED_FILL_RGB, null));
      redFill.setFillPattern(FillPatternType.SOLID_FOREGROUND);

      List<FeatureParameter> targetFeatures = featureConfigService.getFeaturesByType(analysisType);

      Row headerRow = sheet.createRow(0);
      int idx = 0;
      for (; idx < HEADERS.length; idx++) {
        Cell c = headerRow.createCell(idx);
        c.setCellValue(HEADERS[idx]);
        c.setCellStyle(header);
      }
      for (int j = 0; j < targetFeatures.size(); j++) {
        Cell c = headerRow.createCell(idx + j);
        c.setCellValue(targetFeatures.get(j).getName());
        c.setCellStyle(header);
      }

      int rowIdx = 1;
      for (VideoMetadataEntity v : videos) {
        Row r = sheet.createRow(rowIdx++);
        int colIdx = 0;

        writeString(r, colIdx++, v.getBrand());
        writeString(r, colIdx++, v.getAssetName());
        writeString(r, colIdx++, v.getVertical());
        writeString(r, colIdx++, humanSource(v.getSourceType()));
        writeString(r, colIdx++, v.getVideoLanguage());
        writeString(r, colIdx++, v.getVideoUrl());
        writeString(r, colIdx++, "Still have to figure out");
        writeString(r, colIdx++, v.getProduct());
        writeString(r, colIdx++, v.getProduct());
        writeString(r, colIdx++, v.getRecommendations());

        for (int j = 0; j < targetFeatures.size(); j++) {
          if (v.getFeatures() != null
              && v.getFeatures().contains(targetFeatures.get(j).getName())) {
            writeBooleanScore(r, colIdx++, "true", redFill);
          } else {
            writeBooleanScore(r, colIdx++, "false", redFill);
          }
        }
      }

      for (int i = 0; i < HEADERS.length + targetFeatures.size(); i++) sheet.autoSizeColumn(i);
      sheet.createFreezePane(0, 1);

      wb.write(out);
      return out.toByteArray();
    }
  }

  public String generateSheetInUserDrive(String analysisId, String userAccessToken)
      throws IOException {
    byte[] xlsxBytes = generateXlsxBytes(analysisId);
    Drive drive = userApis.drive(userAccessToken);

    AnalysisRequestEntity analysis = analysisRequestRepository.findById(analysisId).block();
    String brand =
        analysis == null
            ? analysisId
            : firstNonBlank(analysis.getBrandName(), analysis.getAnalysisName(), analysisId);

    File fileMetadata =
        new File().setName("Bulk AiBCD report - " + brand).setMimeType(GOOGLE_SHEET_MIME);
    InputStreamContent media =
        new InputStreamContent(XLSX_MIME, new ByteArrayInputStream(xlsxBytes));
    File uploaded = drive.files().create(fileMetadata, media).setFields("id").execute();
    String id = uploaded.getId();

    log.info("GoogleSheetsClient: Generated Sheet in user Drive for {} → id={}", analysisId, id);
    return "https://docs.google.com/spreadsheets/d/" + id;
  }

  private static void writeString(Row r, int col, String value) {
    Cell c = r.createCell(col);
    c.setCellValue(value == null ? "" : value);
  }

  private static void writeBooleanScore(Row r, int col, String score, CellStyle redFill) {
    Cell c = r.createCell(col);
    if (score == null) {
      c.setBlank();
      c.setCellStyle(redFill);
    } else {
      c.setCellValue(score);
      if ("false".equals(score)) c.setCellStyle(redFill);
    }
  }

  private static Integer avg(VideoMetadataEntity v) {
    int sum = 0, n = 0;
    Integer[] s = {v.getAScore(), v.getBScore(), v.getCScore(), v.getDScore()};
    for (Integer x : s)
      if (x != null) {
        sum += x;
        n++;
      }
    return n == 0 ? null : Math.round((float) sum / n);
  }

  private static String humanSource(String src) {
    if (src == null) return "";
    try {
      SourceType st = SourceType.valueOf(src.toUpperCase());
      return switch (st) {
        case YOUTUBE -> "YouTube";
        case DRIVE -> "Drive";
        case FILE -> "Upload";
        case ID -> "Ads ID";
      };
    } catch (IllegalArgumentException e) {
      return src;
    }
  }

  private static String firstNonBlank(String... candidates) {
    for (String c : candidates) if (c != null && !c.isBlank()) return c;
    return "";
  }
}
