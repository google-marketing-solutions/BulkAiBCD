package com.bulkaibcd.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bulkaibcd.enums.RawMetadataType;
import org.junit.jupiter.api.Test;

class AnalysisConstantsTest {

  @Test
  void testConstantsAndPromptMap() {
    assertThat(AnalysisConstants.METADATA_TYPES).hasSize(14);

    for (RawMetadataType type : RawMetadataType.values()) {
      assertThat(AnalysisConstants.METADATA_TYPES).contains(type.name());
      assertThat(AnalysisConstants.RAW_PROMPT_MAP).containsKey(type.name());
      assertThat(AnalysisConstants.RAW_PROMPT_MAP.get(type.name())).isNotBlank();
    }

    assertThat(AnalysisConstants.BRAND_PROMPT).contains("brand");
    assertThat(AnalysisConstants.PRODUCT_PROMPT).contains("product");
    assertThat(AnalysisConstants.LANGUAGE_PROMPT).contains("language");
    assertThat(AnalysisConstants.VERTICAL_PROMPT).contains("vertical");
    assertThat(AnalysisConstants.ASSET_NAME_PROMPT).contains("title");
    assertThat(AnalysisConstants.SPEECH_TRANSCRIPTION_PROMPT).contains("transcript");
    assertThat(AnalysisConstants.TEXT_DETECTION_PROMPT).contains("text");
    assertThat(AnalysisConstants.SHOT_CHANGE_DETECTION_PROMPT).contains("shot");
    assertThat(AnalysisConstants.LOGO_RECOGNITION_PROMPT).contains("logo");
    assertThat(AnalysisConstants.OBJECT_TRACKING_PROMPT).contains("object");
    assertThat(AnalysisConstants.FACE_DETECTION_PROMPT).contains("face");
    assertThat(AnalysisConstants.PERSON_DETECTION_PROMPT).contains("person");
    assertThat(AnalysisConstants.LABEL_DETECTION_PROMPT).contains("label");
    assertThat(AnalysisConstants.EXPLICIT_CONTENT_DETECTION_PROMPT).contains("explicit");
    assertThat(AnalysisConstants.SINGLE_FEATURE_PROMPT_TEMPLATE).contains("%s");
  }
}
