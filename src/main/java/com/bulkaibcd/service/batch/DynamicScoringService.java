/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
        case ACTION -> r.getAction() == 1;
        default -> r.getCore() == 1; // CORE_UNKNOWN
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
