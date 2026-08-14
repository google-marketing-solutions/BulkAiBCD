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

package com.bulkaibcd.service.analysis;

import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.bulkaibcd.service.UploadUrlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service responsible for purging an analysis run and all associated records.
 * <p>
 * Performs throttled deletions of child video inputs, video metadata, parent analysis requests,
 * and cleans up temporary GCS raw video uploads.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DeleteAnalysisService implements ApiService<String, ResponseEntity<String>> {

  private final VideoInputRepository videoInputRepository;
  private final VideoMetadataRepository videoMetadataRepository;
  private final AnalysisRequestRepository analysisRequestRepository;
  private final ObjectProvider<UploadUrlService> uploadUrlServiceProvider;

  /**
   * Executes the deletion workflow for the specified analysis ID.
   *
   * @param analysisId The unique identifier of the analysis to delete
   * @return A reactive {@link Mono} emitting the HTTP response confirming deletion
   */
  @Override
  public Mono<ResponseEntity<String>> execute(String analysisId) {
    log.info("DeleteAnalysisService: Initiating purge for analysisId: {}", analysisId);
    UploadUrlService uploads = uploadUrlServiceProvider.getIfAvailable();

    return videoInputRepository.findByAnalysisId(analysisId)
        .flatMap(
            videoInputEntity -> {
              return Mono.fromCallable(() -> {
                if (uploads != null
                    && videoInputEntity.getGcsObjectId() != null
                    && !videoInputEntity.getGcsObjectId().isEmpty()) {
                  uploads.delete(videoInputEntity.getGcsObjectId());
                }
                return videoInputEntity;
              })
              .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
              .flatMap(input -> videoInputRepository.deleteById(input.getId())
                  .then(videoMetadataRepository.deleteById(input.getId())));
            }, 8)
        .then(analysisRequestRepository.deleteById(analysisId))
        .thenReturn(ResponseEntity.ok("DELETED"));
  }
}
