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

package com.bulkaibcd.mapper;

import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.SubmitAnalysisRequest;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.model.VideoMetadataEntity;
import com.google.cloud.Timestamp;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class EntityMapper {

  public static AnalysisRequestEntity toAnalysisRequestEntity(SubmitAnalysisRequest request) {

    Timestamp now = Timestamp.now();
    String analysisId = UUID.randomUUID().toString();
    return AnalysisRequestEntity.builder()
        .analysisId(analysisId)
        .requesterId(request.getRequesterId())
        .analysisName(request.getAnalysisName())
        .analysisType(request.getAnalysisType())
        .analysisStatus(AnalysisStatus.PENDING.name())
        .brandName(request.getBrandName())
        .marketingObjective(request.getMarketingObjective())
        .customFeaturesLong(request.getCustomFeaturesLong())
        .customFeaturesShort(request.getCustomFeaturesShort())
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  public static VideoInputEntity toVideoInputEntity(
      SubmitAnalysisRequest.VideoInput videoInput, String analysisId) {

    String videoId = UUID.randomUUID().toString();
    return VideoInputEntity.builder()
        .id(analysisId + "_" + videoId)
        .analysisId(analysisId)
        .videoId(videoId)
        .videoName(videoInput.getVideoName())
        .videoUrl(videoInput.getVideoUrl())
        .thumbnailUrl(videoInput.getThumbnailUrl())
        .sourceType(videoInput.getSourceType())
        .format(videoInput.getFormat())
        .gcsObjectId(videoInput.getGcsObjectId())
        .build();
  }

  public static VideoMetadataEntity toVideoMetadataEntity(String analysisId, String videoId) {

    return VideoMetadataEntity.builder()
        .id(analysisId + "_" + videoId)
        .analysisId(analysisId)
        .videoId(videoId)
        .status(AnalysisStatus.PROCESSING.name())
        .build();
  }

  public static VideoMetadataEntity toVideoMetadataEntity(VideoInputEntity v, boolean hasError) {

    String docId = v.getAnalysisId() + "_" + v.getVideoId();
    VideoMetadataEntity.VideoMetadataEntityBuilder videoMetadataEntityBuilder =
        VideoMetadataEntity.builder()
            .id(docId)
            .analysisId(v.getAnalysisId())
            .videoId(v.getVideoId())
            .videoName(v.getVideoName())
            .videoUrl(v.getVideoUrl())
            .gcsObjectId(v.getGcsObjectId())
            .thumbnailUrl(v.getThumbnailUrl())
            .sourceType(v.getSourceType())
            .format(v.getFormat())
            .status(hasError ? AnalysisStatus.COMPLETED.name() : AnalysisStatus.PROCESSING.name());
    if (hasError) {
      videoMetadataEntityBuilder
          .errorMessage(v.getErrorMessage())
          .assetName("")
          .aScore(0)
          .bScore(0)
          .cScore(0)
          .dScore(0);
    }
    return videoMetadataEntityBuilder.build();
  }
}
