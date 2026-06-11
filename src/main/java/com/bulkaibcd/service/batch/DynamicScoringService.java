package com.bulkaibcd.service.batch;

import com.bulkaibcd.enums.MarketingObjective;
import com.bulkaibcd.model.GuidelineRelevance;
import com.bulkaibcd.model.VideoMetadataEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service responsible for encapsulating all ABCD dynamic scoring math calculations and guideline
 * relevance evaluations.
 */
@Service
@Slf4j
public class DynamicScoringService {

  public boolean isGuidelineRelevant(GuidelineRelevance r, String objective) {
    if (objective == null) return r.getCore() == 1;
    try {
      MarketingObjective mo = MarketingObjective.valueOf(objective.toUpperCase());
      return switch (mo) {
        case AWARENESS -> r.getAwareness() == 1;
        case CONSIDERATION -> r.getConsideration() == 1;
        case CONVERSION -> r.getAction() == 1;
        default -> r.getCore() == 1; // CORE_UNKNOWN, BRAND_BUILDING
      };
    } catch (IllegalArgumentException e) {
      return r.getCore() == 1;
    }
  }

  public int calculateDimensionScore(int total, int hit) {
    return total > 0 ? (int) Math.round((double) hit / total * 100.0) : 100;
  }

  public Integer calculateAverageScore(VideoMetadataEntity v) {
    if (v == null) return null;
    int sum = 0, n = 0;
    Integer[] scores = {v.getAScore(), v.getBScore(), v.getCScore(), v.getDScore()};
    for (Integer s : scores) {
      if (s != null) {
        sum += s;
        n++;
      }
    }
    return n == 0 ? null : Math.round((float) sum / n);
  }
}
