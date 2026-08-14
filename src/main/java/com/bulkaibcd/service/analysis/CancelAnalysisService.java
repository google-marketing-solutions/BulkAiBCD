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

import com.bulkaibcd.enums.AnalysisStatus;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoMetadataRepository;
import com.google.cloud.Timestamp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service responsible for cancelling an in-flight analysis run.
 *
 * <p>Marks the parent analysis and its active video metadata records as CANCELLED. Any
 * already-enqueued Cloud Tasks short-circuit upon detecting this status.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CancelAnalysisService implements ApiService<String, ResponseEntity<String>> {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoMetadataRepository videoMetadataRepository;

  /**
   * Executes the cancellation workflow for the specified analysis ID.
   *
   * @param analysisId The unique identifier of the analysis to cancel
   * @return A reactive {@link Mono} emitting the HTTP response indicating cancellation status
   */
  @Override
  public Mono<ResponseEntity<String>> execute(String analysisId) {
    log.info("CancelAnalysisService: Initiating cancellation for analysisId: {}", analysisId);
    Timestamp now = Timestamp.now();

    return analysisRequestRepository
        .findById(analysisId)
        .flatMap(
            parent -> {
              if (AnalysisStatus.COMPLETED.name().equals(parent.getAnalysisStatus())
                  || AnalysisStatus.CANCELLED.name().equals(parent.getAnalysisStatus())) {
                log.info(
                    "CancelAnalysisService: Analysis {} is already terminal ({})",
                    analysisId,
                    parent.getAnalysisStatus());
                return Mono.just(ResponseEntity.ok(parent.getAnalysisStatus()));
              }
              parent.setAnalysisStatus(AnalysisStatus.CANCELLED.name());
              parent.setUpdatedAt(now);
              return analysisRequestRepository
                  .save(parent)
                  .then(
                      videoMetadataRepository
                          .findByAnalysisId(analysisId)
                          .flatMap(
                              videoMetadata -> {
                                if (!AnalysisStatus.COMPLETED
                                    .name()
                                    .equals(videoMetadata.getStatus())) {
                                  videoMetadata.setStatus(AnalysisStatus.CANCELLED.name());
                                  if (videoMetadata.getErrorMessage() == null) {
                                    videoMetadata.setErrorMessage("cancelled by user");
                                  }
                                  return videoMetadataRepository.save(videoMetadata);
                                }
                                return Mono.just(videoMetadata);
                              })
                          .then())
                  .thenReturn(ResponseEntity.ok(AnalysisStatus.CANCELLED.name()));
            })
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }
}
