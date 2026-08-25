package com.bulkaibcd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bulkaibcd.enums.VideoFormat;
import com.bulkaibcd.model.FeatureParameter;
import com.bulkaibcd.model.GuidelineRelevance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

class FeatureConfigServiceTest {

  private ResourceLoader resourceLoader;
  private ObjectMapper objectMapper;
  private FeatureConfigService service;

  private static final String SAMPLE_FEATURES_JSON = """
      [
        {
          "id": "a_1",
          "name": "Feature A1",
          "type": "STANDARD",
          "supportedFormats": ["LONG", "SHORT"]
        },
        {
          "id": "b_1",
          "name": "Feature B1",
          "type": "LIGHT",
          "supportedFormats": ["LONG"]
        },
        {
          "id": "c_1",
          "name": "Feature C1",
          "type": "STANDARD",
          "supportedFormats": []
        }
      ]
      """;

  private static final String SAMPLE_RELEVANCE_JSON = """
      [
        {
          "parameter_id": "a_1",
          "core": 1,
          "awareness": 1,
          "consideration": 0,
          "action": 1
        }
      ]
      """;

  @BeforeEach
  void setUp() throws Exception {
    resourceLoader = mock(ResourceLoader.class);
    objectMapper = new ObjectMapper();

    Resource featuresResource = mock(Resource.class);
    when(featuresResource.getInputStream())
        .thenAnswer(inv -> new ByteArrayInputStream(SAMPLE_FEATURES_JSON.getBytes(StandardCharsets.UTF_8)));
    when(resourceLoader.getResource("classpath:config/features.json")).thenReturn(featuresResource);

    Resource relevanceResource = mock(Resource.class);
    when(relevanceResource.getInputStream())
        .thenAnswer(inv -> new ByteArrayInputStream(SAMPLE_RELEVANCE_JSON.getBytes(StandardCharsets.UTF_8)));
    when(resourceLoader.getResource("classpath:config/guideline_relevance.json")).thenReturn(relevanceResource);

    service = new FeatureConfigService(resourceLoader, objectMapper);
    service.init();
  }

  @Test
  void getAllFeaturesReturnsLoadedFeatures() {
    List<FeatureParameter> features = service.getAllFeatures();
    assertThat(features).hasSize(3);
  }

  @Test
  void getFeatureByIdReturnsMatchingFeature() {
    FeatureParameter f = service.getFeatureById("a_1");
    assertThat(f).isNotNull();
    assertThat(f.getName()).isEqualTo("Feature A1");

    assertThat(service.getFeatureById("nonexistent")).isNull();
  }

  @Test
  void getGuidelineRelevanceMapReturnsLoadedRelevance() {
    Map<String, GuidelineRelevance> map = service.getGuidelineRelevanceMap();
    assertThat(map).containsKey("a_1");
    assertThat(map.get("a_1").getAwareness()).isEqualTo(1);
  }

  @Test
  void getFeaturesByTypeAndFormat() {
    List<FeatureParameter> standardLong = service.getFeaturesByTypeAndFormat("STANDARD", VideoFormat.LONG);
    assertThat(standardLong).hasSize(2); // a_1 and c_1 (empty supportedFormats defaults to LONG)

    List<FeatureParameter> standardShort = service.getFeaturesByTypeAndFormat("STANDARD", VideoFormat.SHORT);
    assertThat(standardShort).hasSize(1);
    assertThat(standardShort.get(0).getId()).isEqualTo("a_1");

    List<FeatureParameter> lightLong = service.getFeaturesByType("LIGHT");
    assertThat(lightLong).hasSize(1);
    assertThat(lightLong.get(0).getId()).isEqualTo("b_1");

    // "custom" maps to "standard"
    List<FeatureParameter> customFeatures = service.getFeaturesByType("custom");
    assertThat(customFeatures).hasSize(2);

    assertThat(service.getFeaturesByTypeAndFormat(null, VideoFormat.LONG)).isEmpty();
  }

  @Test
  void getFeaturesByTypeWithoutFormat() {
    List<FeatureParameter> list = service.getFeaturesByTypeWithoutFormat("STANDARD");
    assertThat(list).hasSize(2);

    assertThat(service.getFeaturesByTypeWithoutFormat(null)).isEmpty();
  }
}
