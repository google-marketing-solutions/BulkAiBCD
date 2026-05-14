package com.bulkaibcd.service;

import com.bulkaibcd.config.UserGoogleApiFactory;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
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
import org.springframework.stereotype.Service;

/**
 * Builds the "Detailed Spreadsheet" export for an analysis.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link #generateXlsxBytes} — builds an {@code .xlsx} with Apache POI and returns
 *       bytes (unchanged; used internally as the upload payload).
 *   <li>{@link #generateSheetInUserDrive} — uploads the XLSX bytes to the signed-in user's
 *       Drive with {@code mimeType=application/vnd.google-apps.spreadsheet} so Drive
 *       auto-converts to a native Google Sheet owned by the user. Returns the Sheet URL.
 * </ul>
 *
 * <p>The data grid and red-fill highlighting match the original design.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GoogleSpreadsheetService {

  private static final String[] HEADERS = {
    "Video ID",
    "Video Name",
    "Source",
    "Asset Name",
    "A (Attract)",
    "B (Brand)",
    "C (Connect)",
    "D (Direct)",
    "Avg ABCD",
    "Status",
    "Error"
  };

  private static final byte[] RED_FILL_RGB = new byte[] {(byte) 250, (byte) 210, (byte) 207};

  private static final String XLSX_MIME =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  private static final String GOOGLE_SHEET_MIME = "application/vnd.google-apps.spreadsheet";

  private final UserGoogleApiFactory userApis;
  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoMetadataRepository videoMetadataRepository;

  // -- XLSX path (working default) -----------------------------------------

  /** Returns the .xlsx bytes for an analysis. Blocking; callers schedule on boundedElastic. */
  public byte[] generateXlsxBytes(String analysisId) throws IOException {
    AnalysisRequestEntity analysis =
        analysisRequestRepository.findById(analysisId).block();
    if (analysis == null) throw new IOException("Analysis not found: " + analysisId);
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

      Row headerRow = sheet.createRow(0);
      for (int i = 0; i < HEADERS.length; i++) {
        Cell c = headerRow.createCell(i);
        c.setCellValue(HEADERS[i]);
        c.setCellStyle(header);
      }

      int rowIdx = 1;
      for (VideoMetadataEntity v : videos) {
        Row r = sheet.createRow(rowIdx++);
        writeString(r, 0, v.getVideoId());
        writeString(r, 1, v.getVideoName());
        writeString(r, 2, humanSource(v.getSourceType()));
        writeString(r, 3, v.getAssetName());
        writeScore(r, 4, v.getAScore(), redFill);
        writeScore(r, 5, v.getBScore(), redFill);
        writeScore(r, 6, v.getCScore(), redFill);
        writeScore(r, 7, v.getDScore(), redFill);
        writeScore(r, 8, avg(v), redFill);
        writeString(r, 9, v.getStatus());
        Cell err = r.createCell(10);
        if (v.getErrorMessage() != null && !v.getErrorMessage().isEmpty()) {
          err.setCellValue(v.getErrorMessage());
          err.setCellStyle(redFill);
        }
      }

      for (int i = 0; i < HEADERS.length; i++) sheet.autoSizeColumn(i);
      sheet.createFreezePane(0, 1);

      wb.write(out);
      return out.toByteArray();
    }
  }

  // -- User-Drive upload path ----------------------------------------------

  /**
   * Builds the XLSX in memory, uploads it to the signed-in user's Drive with
   * {@code mimeType=application/vnd.google-apps.spreadsheet} so Drive auto-converts
   * to a native Google Sheet owned by the user, then returns the Sheets URL.
   *
   * <p>No {@code parents} → the sheet lands in the user's My Drive root. No explicit
   * sharing — the user already owns it, and IAP handles who can open the app URL.
   *
   * @param userAccessToken Firebase-Auth-minted OAuth token with {@code drive.file} scope.
   */
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
    InputStreamContent media = new InputStreamContent(XLSX_MIME, new ByteArrayInputStream(xlsxBytes));
    File uploaded = drive.files().create(fileMetadata, media).setFields("id").execute();
    String id = uploaded.getId();

    log.info("Generated Sheet in user Drive for {} → id={}", analysisId, id);
    return "https://docs.google.com/spreadsheets/d/" + id;
  }

  // -- Helpers --------------------------------------------------------------

  private static void writeString(Row r, int col, String value) {
    Cell c = r.createCell(col);
    c.setCellValue(value == null ? "" : value);
  }

  private static void writeScore(Row r, int col, Integer score, CellStyle redFill) {
    Cell c = r.createCell(col);
    if (score == null) {
      c.setBlank();
      c.setCellStyle(redFill);
    } else {
      c.setCellValue(score);
      if (score == 0) c.setCellStyle(redFill);
    }
  }

  private static Integer avg(VideoMetadataEntity v) {
    int sum = 0, n = 0;
    Integer[] s = {v.getAScore(), v.getBScore(), v.getCScore(), v.getDScore()};
    for (Integer x : s) if (x != null) { sum += x; n++; }
    return n == 0 ? null : Math.round((float) sum / n);
  }

  private static String humanSource(String src) {
    if (src == null) return "";
    return switch (src) {
      case "youtube" -> "YouTube";
      case "drive" -> "Drive";
      case "file" -> "Upload";
      case "id" -> "Ads ID";
      default -> src;
    };
  }

  private static String firstNonBlank(String... candidates) {
    for (String c : candidates) if (c != null && !c.isBlank()) return c;
    return "";
  }
}
