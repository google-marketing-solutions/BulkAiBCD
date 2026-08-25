package com.bulkaibcd.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EnumsTest {

  @Test
  void testAnalysisStatusEnum() {
    assertThat(AnalysisStatus.valueOf("PENDING")).isEqualTo(AnalysisStatus.PENDING);
    assertThat(AnalysisStatus.valueOf("PROCESSING")).isEqualTo(AnalysisStatus.PROCESSING);
    assertThat(AnalysisStatus.valueOf("METADATA_EXTRACTED")).isEqualTo(AnalysisStatus.METADATA_EXTRACTED);
    assertThat(AnalysisStatus.valueOf("BATCH_QUEUED")).isEqualTo(AnalysisStatus.BATCH_QUEUED);
    assertThat(AnalysisStatus.valueOf("COMPLETED")).isEqualTo(AnalysisStatus.COMPLETED);
    assertThat(AnalysisStatus.valueOf("FAILED")).isEqualTo(AnalysisStatus.FAILED);
    assertThat(AnalysisStatus.valueOf("CANCELLED")).isEqualTo(AnalysisStatus.CANCELLED);
    assertThat(AnalysisStatus.valueOf("DELETED")).isEqualTo(AnalysisStatus.DELETED);
    assertThat(AnalysisStatus.values()).hasSize(8);
  }

  @Test
  void testAnalysisTypeEnum() {
    assertThat(AnalysisType.valueOf("STANDARD")).isEqualTo(AnalysisType.STANDARD);
    assertThat(AnalysisType.valueOf("LIGHT")).isEqualTo(AnalysisType.LIGHT);
    assertThat(AnalysisType.values()).hasSize(2);
  }

  @Test
  void testMarketingObjectiveEnum() {
    assertThat(MarketingObjective.valueOf("CORE_UNKNOWN")).isEqualTo(MarketingObjective.CORE_UNKNOWN);
    assertThat(MarketingObjective.valueOf("AWARENESS")).isEqualTo(MarketingObjective.AWARENESS);
    assertThat(MarketingObjective.valueOf("CONSIDERATION")).isEqualTo(MarketingObjective.CONSIDERATION);
    assertThat(MarketingObjective.valueOf("ACTION")).isEqualTo(MarketingObjective.ACTION);
    assertThat(MarketingObjective.values()).hasSize(4);
  }

  @Test
  void testRawMetadataTypeEnum() {
    assertThat(RawMetadataType.valueOf("BRAND")).isEqualTo(RawMetadataType.BRAND);
    assertThat(RawMetadataType.valueOf("PRODUCT")).isEqualTo(RawMetadataType.PRODUCT);
    assertThat(RawMetadataType.valueOf("LANGUAGE")).isEqualTo(RawMetadataType.LANGUAGE);
    assertThat(RawMetadataType.valueOf("VERTICAL")).isEqualTo(RawMetadataType.VERTICAL);
    assertThat(RawMetadataType.valueOf("ASSET_NAME")).isEqualTo(RawMetadataType.ASSET_NAME);
    assertThat(RawMetadataType.valueOf("SPEECH_TRANSCRIPTION")).isEqualTo(RawMetadataType.SPEECH_TRANSCRIPTION);
    assertThat(RawMetadataType.valueOf("TEXT_DETECTION")).isEqualTo(RawMetadataType.TEXT_DETECTION);
    assertThat(RawMetadataType.valueOf("SHOT_CHANGE_DETECTION")).isEqualTo(RawMetadataType.SHOT_CHANGE_DETECTION);
    assertThat(RawMetadataType.valueOf("LOGO_RECOGNITION")).isEqualTo(RawMetadataType.LOGO_RECOGNITION);
    assertThat(RawMetadataType.valueOf("OBJECT_TRACKING")).isEqualTo(RawMetadataType.OBJECT_TRACKING);
    assertThat(RawMetadataType.valueOf("FACE_DETECTION")).isEqualTo(RawMetadataType.FACE_DETECTION);
    assertThat(RawMetadataType.valueOf("PERSON_DETECTION")).isEqualTo(RawMetadataType.PERSON_DETECTION);
    assertThat(RawMetadataType.valueOf("LABEL_DETECTION")).isEqualTo(RawMetadataType.LABEL_DETECTION);
    assertThat(RawMetadataType.valueOf("EXPLICIT_CONTENT_DETECTION")).isEqualTo(RawMetadataType.EXPLICIT_CONTENT_DETECTION);
    assertThat(RawMetadataType.values()).hasSize(14);
  }

  @Test
  void testScoringDimensionEnum() {
    assertThat(ScoringDimension.valueOf("A_ATTRACT")).isEqualTo(ScoringDimension.A_ATTRACT);
    assertThat(ScoringDimension.valueOf("B_BRAND")).isEqualTo(ScoringDimension.B_BRAND);
    assertThat(ScoringDimension.valueOf("C_CONNECT")).isEqualTo(ScoringDimension.C_CONNECT);
    assertThat(ScoringDimension.valueOf("D_DIRECT")).isEqualTo(ScoringDimension.D_DIRECT);
    assertThat(ScoringDimension.valueOf("ASSET_NAME")).isEqualTo(ScoringDimension.ASSET_NAME);
    assertThat(ScoringDimension.values()).hasSize(5);
  }

  @Test
  void testSourceTypeEnum() {
    assertThat(SourceType.valueOf("YOUTUBE")).isEqualTo(SourceType.YOUTUBE);
    assertThat(SourceType.valueOf("DRIVE")).isEqualTo(SourceType.DRIVE);
    assertThat(SourceType.valueOf("FILE")).isEqualTo(SourceType.FILE);
    assertThat(SourceType.valueOf("ID")).isEqualTo(SourceType.ID);
    assertThat(SourceType.values()).hasSize(4);
  }

  @Test
  void testVideoFormatEnum() {
    assertThat(VideoFormat.valueOf("LONG")).isEqualTo(VideoFormat.LONG);
    assertThat(VideoFormat.valueOf("SHORT")).isEqualTo(VideoFormat.SHORT);
    assertThat(VideoFormat.values()).hasSize(2);
  }
}

