package com.bulkaibcd.service;

import com.bulkaibcd.model.FeatureParameter;
import com.bulkaibcd.model.GuidelineRelevance;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class FeatureConfigService {

  private final ResourceLoader resourceLoader;
  private final ObjectMapper objectMapper;

  private List<FeatureParameter> allFeatures = Collections.emptyList();
  private Map<String, FeatureParameter> featuresMap = Collections.emptyMap();
  private Map<String, GuidelineRelevance> relevanceMap = Collections.emptyMap();

  @PostConstruct
  public void init() {
    log.info("FeatureConfigService: Starting startup loading of features and dynamic dynamic-scoring guidelines...");
    try {
      // 1. Load and parse features.json
      Resource featuresResource = resourceLoader.getResource("classpath:config/features.json");
      try (InputStream is = featuresResource.getInputStream()) {
        allFeatures = objectMapper.readValue(is, new TypeReference<List<FeatureParameter>>() {
        });
        featuresMap = allFeatures.stream()
            .collect(Collectors.toMap(FeatureParameter::getId, f -> f, (f1, f2) -> f1));
        log.info("FeatureConfigService: Successfully loaded {} creative features from resource.", allFeatures.size());
      }

      // 2. Load and parse guideline_relevance.json
      Resource relevanceResource = resourceLoader.getResource("classpath:config/guideline_relevance.json");
      try (InputStream is = relevanceResource.getInputStream()) {
        List<GuidelineRelevance> relevanceList = objectMapper.readValue(is,
            new TypeReference<List<GuidelineRelevance>>() {
            });
        relevanceMap = relevanceList.stream()
            .collect(Collectors.toMap(GuidelineRelevance::getParameterId, r -> r, (r1, r2) -> r1));
        log.info("FeatureConfigService: Successfully loaded {} dynamic dynamic-scoring guidelines from resource.",
            relevanceMap.size());
      }

    } catch (Exception e) {
      log.error("FeatureConfigService: Critical failure during initialization loaders!", e);
      throw new RuntimeException("Failed to load creative configs", e);
    }
  }

  public List<FeatureParameter> getAllFeatures() {
    return allFeatures;
  }

  public List<FeatureParameter> getFeaturesByType(String type) {
    if (type == null)
      return Collections.emptyList();
    final String ftype = type.equals("custom") ? "standard" : type;
    return allFeatures.stream()
        .filter(f -> ftype.equalsIgnoreCase(f.getType()))
        .collect(Collectors.toList());
  }

  public FeatureParameter getFeatureById(String id) {
    return featuresMap.get(id);
  }

  public Map<String, GuidelineRelevance> getGuidelineRelevanceMap() {
    return relevanceMap;
  }
}
