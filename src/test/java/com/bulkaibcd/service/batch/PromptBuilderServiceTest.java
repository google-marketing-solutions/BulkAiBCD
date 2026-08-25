package com.bulkaibcd.service.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.bulkaibcd.enums.RawMetadataType;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.FeatureParameter;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptBuilderServiceTest {

  private ObjectMapper objectMapper;
  private PromptBuilderService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    service = new PromptBuilderService(objectMapper);
  }

  @Test
  void stripQuotesRemovesLeadingAndTrailingDoubleQuotes() {
    assertThat(PromptBuilderService.stripQuotes("\"hello world\"")).isEqualTo("hello world");
    assertThat(PromptBuilderService.stripQuotes("hello world")).isEqualTo("hello world");
    assertThat(PromptBuilderService.stripQuotes(null)).isNull();
    assertThat(PromptBuilderService.stripQuotes("\"\"")).isEqualTo("");
  }

  @Test
  void buildMetadataJsonCombinesFieldsAndAnnotations() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().brandName("ParentBrand").build();

    VideoMetadataEntity metadata =
        VideoMetadataEntity.builder()
            .brand("ChildBrand")
            .product("Pixel 8")
            .videoLanguage("en")
            .vertical("Tech")
            .assetName("Launch Spot")
            .text("[{\"text\":\"Hello\",\"start_time\":0.0,\"end_time\":2.0}]")
            .speech("[{\"transcript\":\"Welcome\",\"confidence\":0.95}]")
            .build();

    String json = service.buildMetadataJson(metadata, parent);
    assertThat(json).contains("\"brand\":\"ChildBrand\"");
    assertThat(json).contains("\"product\":\"Pixel 8\"");
    assertThat(json).contains("\"textAnnotations\":[{\"text\":\"Hello\",\"start_time\":0.0,\"end_time\":2.0}]");
    assertThat(json).contains("\"speechAnnotations\":[{\"transcript\":\"Welcome\",\"confidence\":0.95}]");
  }

  @Test
  void buildMetadataJsonFallbackToParentBrandWhenChildBrandIsUnknown() {
    AnalysisRequestEntity parent =
        AnalysisRequestEntity.builder().brandName("FallbackBrand").build();

    VideoMetadataEntity metadata =
        VideoMetadataEntity.builder().brand("unknown").build();

    String json = service.buildMetadataJson(metadata, parent);
    assertThat(json).contains("\"brand\":\"FallbackBrand\"");
  }

  @Test
  void createMetadataSummaryFormatsAnnotations() {
    VideoMetadataEntity metadata =
        VideoMetadataEntity.builder()
            .speech("[{\"transcript\":\"Buy now\",\"confidence\":0.9}]")
            .text("[{\"text\":\"Discount 50%\",\"start_time\":0.0,\"end_time\":3.0}]")
            .logo("[{\"logo_description\":\"Google\",\"start_time\":0.0,\"end_time\":2.0}]")
            .shot("[{\"start_time\":0.0,\"end_time\":1.5}]")
            .objects("[{\"object_description\":\"Phone\",\"start_time\":0.5,\"end_time\":2.5}]")
            .face("[{\"start_time\":1.0,\"end_time\":2.0,\"confidence\":0.99}]")
            .person("[{\"start_time\":0.0,\"end_time\":2.0,\"frames\":[{\"box\":{\"l\":0.1,\"r\":0.5,\"t\":0.1,\"b\":0.5}}]}]")
            .labelName("[{\"label\":\"Smartphone\",\"start_time\":0.0,\"end_time\":2.0,\"confidence\":0.95}]")
            .explicit("[{\"category\":\"violence\",\"likelihood\":\"VERY_UNLIKELY\",\"start_time\":0.0,\"end_time\":1.0}]")
            .build();

    String summary = service.createMetadataSummary(metadata);
    assertThat(summary).contains("SPEECH TRANSCRIPT");
    assertThat(summary).contains("TEXT DETECTED");
    assertThat(summary).contains("LOGO DETECTED");
    assertThat(summary).contains("SHOT CHANGES");
    assertThat(summary).contains("OBJECTS DETECTED");
    assertThat(summary).contains("FACES DETECTED");
    assertThat(summary).contains("PERSONS DETECTED");
    assertThat(summary).contains("LABELS DETECTED");
    assertThat(summary).contains("EXPLICIT CONTENT DETECTED");
  }

  @Test
  void generateSingleFeaturePromptReplacesPlaceholders() {
    FeatureParameter feature =
        FeatureParameter.builder()
            .name("Early Brand Presence")
            .criteria("Brand shown within 5s")
            .promptTemplate("Is {brand} shown promoting {product}?\n{metadata_summary}")
            .build();

    VideoMetadataEntity metadata =
        VideoMetadataEntity.builder()
            .brand("Acme")
            .product("Widget")
            .vertical("Retail")
            .videoLanguage("en")
            .assetName("Spot 1")
            .build();

    String prompt = service.generateSingleFeaturePrompt(feature, metadata, "Summary text");
    assertThat(prompt).contains("Early Brand Presence");
    assertThat(prompt).contains("Acme");
    assertThat(prompt).contains("Widget");
    assertThat(prompt).contains("Summary text");
  }

  @Test
  void createRequestPayloadRowProducesValidJson() throws Exception {
    String row = service.createRequestPayloadRow("gs://bucket/vid.mp4", "prompt text", "inst-1");
    assertThat(row).contains("\"instance\":\"inst-1\"");
    assertThat(row).contains("\"file_uri\":\"gs://bucket/vid.mp4\"");
    assertThat(row).contains("\"text\":\"prompt text\"");
  }

  @Test
  void createPhase1RequestPayloadRowProducesValidJson() {
    String row = service.createPhase1RequestPayloadRow("gs://bucket/vid.mp4", "prompt text", "inst-1", true);
    assertThat(row).contains("\"instance\":\"inst-1\"");
    assertThat(row).contains("\"response_mime_type\":\"application/json\"");
  }

  @Test
  void updateRawMetadataFieldUpdatesAllEnums() {
    VideoMetadataEntity m = new VideoMetadataEntity();
    for (RawMetadataType type : RawMetadataType.values()) {
      service.updateRawMetadataField(m, type.name(), "\"value-for-" + type.name() + "\"");
    }

    assertThat(m.getBrand()).isEqualTo("value-for-BRAND");
    assertThat(m.getProduct()).isEqualTo("value-for-PRODUCT");
    assertThat(m.getVideoLanguage()).isEqualTo("value-for-LANGUAGE");
    assertThat(m.getVertical()).isEqualTo("value-for-VERTICAL");
    assertThat(m.getAssetName()).isEqualTo("value-for-ASSET_NAME");
    assertThat(m.getSpeech()).isEqualTo("value-for-SPEECH_TRANSCRIPTION");
    assertThat(m.getText()).isEqualTo("value-for-TEXT_DETECTION");
    assertThat(m.getShot()).isEqualTo("value-for-SHOT_CHANGE_DETECTION");
    assertThat(m.getLogo()).isEqualTo("value-for-LOGO_RECOGNITION");
    assertThat(m.getObjects()).isEqualTo("value-for-OBJECT_TRACKING");
    assertThat(m.getFace()).isEqualTo("value-for-FACE_DETECTION");
    assertThat(m.getPerson()).isEqualTo("value-for-PERSON_DETECTION");
    assertThat(m.getLabelName()).isEqualTo("value-for-LABEL_DETECTION");
    assertThat(m.getExplicit()).isEqualTo("value-for-EXPLICIT_CONTENT_DETECTION");
  }

  @Test
  void fillTerminalRawFailurePopulatesDefaults() {
    VideoMetadataEntity m = new VideoMetadataEntity();
    for (RawMetadataType type : RawMetadataType.values()) {
      service.fillTerminalRawFailure(m, type.name());
    }

    assertThat(m.getBrand()).isEqualTo("");
    assertThat(m.getProduct()).isEqualTo("");
    assertThat(m.getVideoLanguage()).isEqualTo("zxx");
    assertThat(m.getVertical()).isEqualTo("");
    assertThat(m.getAssetName()).isEqualTo("");
    assertThat(m.getSpeech()).isEqualTo("[]");
    assertThat(m.getText()).isEqualTo("[]");
    assertThat(m.getShot()).isEqualTo("[]");
    assertThat(m.getLogo()).isEqualTo("[]");
    assertThat(m.getObjects()).isEqualTo("[]");
    assertThat(m.getFace()).isEqualTo("[]");
    assertThat(m.getPerson()).isEqualTo("[]");
    assertThat(m.getLabelName()).isEqualTo("[]");
    assertThat(m.getExplicit()).isEqualTo("[]");
  }
}
