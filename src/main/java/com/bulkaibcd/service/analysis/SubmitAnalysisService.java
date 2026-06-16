package com.bulkaibcd.service.analysis;

import com.bulkaibcd.mapper.EntityMapper;
import com.bulkaibcd.model.AnalysisRequestEntity;
import com.bulkaibcd.model.SubmitAnalysisRequest;
import com.bulkaibcd.model.VideoInputEntity;
import com.bulkaibcd.client.CloudTasksQueueClient;
import com.bulkaibcd.repository.AnalysisRequestRepository;
import com.bulkaibcd.repository.VideoInputRepository;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service responsible for orchestrating the submission of new video analysis requests.
 *
 * <p>Handles mapping the incoming request to database entities via {@link EntityMapper}, persisting
 * the parent analysis and child video records, enqueuing the asynchronous preparation worker task,
 * and managing transactional rollback on failure.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubmitAnalysisService
    implements ApiService<SubmitAnalysisRequest, ResponseEntity<String>> {

  private final AnalysisRequestRepository analysisRequestRepository;
  private final VideoInputRepository videoInputRepository;
  private final CloudTasksQueueClient cloudTasksQueueClient;

  /**
   * Executes the analysis submission workflow.
   *
   * @param request The incoming submission payload containing metadata and video inputs
   * @return A reactive {@link Mono} emitting the HTTP response with the generated analysis ID
   */
  @Override
  public Mono<ResponseEntity<String>> execute(SubmitAnalysisRequest request) {
    AnalysisRequestEntity parent = EntityMapper.toAnalysisRequestEntity(request);

    List<SubmitAnalysisRequest.VideoInput> videos =
        request.getVideos() == null ? List.of() : request.getVideos();

    return analysisRequestRepository
        .save(parent)
        .then(persistVideos(parent.getAnalysisId(), videos))
        .then(Mono.defer(() -> enqueuePrepare(parent.getAnalysisId())))
        .onErrorResume(err -> rollback(parent.getAnalysisId(), err));
  }

  /** Persists all child video input records associated with the parent analysis. */
  private Mono<Void> persistVideos(
      String analysisId, List<SubmitAnalysisRequest.VideoInput> videos) {
    log.info(
        "SubmitAnalysisService: Persisting {} video entries for analysisId: {}",
        videos.size(),
        analysisId);
    return Flux.fromIterable(videos)
        .flatMap(
            videoInput -> {
              VideoInputEntity entity = EntityMapper.toVideoInputEntity(videoInput, analysisId);
              return videoInputRepository
                  .save(entity)
                  .doOnSuccess(
                      v ->
                          log.info(
                              "SubmitAnalysisService: Saved VideoInputEntity: {} for video {}",
                              v.getId(),
                              v.getVideoName()));
            })
        .then();
  }

  /** Enqueues the asynchronous preparation task in Cloud Tasks to initiate worker processing. */
  private Mono<ResponseEntity<String>> enqueuePrepare(String analysisId) {
    try {
      String payload = "{\"analysisId\": \"" + analysisId + "\"}";
      log.info(
          "SubmitAnalysisService: Enqueuing prepare task for {}. Payload: {}", analysisId, payload);
      cloudTasksQueueClient.enqueueTask("/api/v2/worker/prepare", payload);
      return Mono.just(ResponseEntity.ok(analysisId));
    } catch (IOException e) {
      return Mono.error(e);
    }
  }

  /** Performs a reactive cleanup of persisted database records if the submission workflow fails. */
  private Mono<ResponseEntity<String>> rollback(String analysisId, Throwable err) {
    log.error(
        "SubmitAnalysisService: Submit failed for {}; rolling back database records",
        analysisId,
        err);
    return videoInputRepository
        .findByAnalysisId(analysisId)
        .flatMap(videoInput -> videoInputRepository.deleteById(videoInput.getId()))
        .then(
            analysisRequestRepository
                .deleteById(analysisId)
                .onErrorResume(
                    delErr -> {
                      log.error(
                          "SubmitAnalysisService: Rollback delete also failed for {}",
                          analysisId,
                          delErr);
                      return Mono.empty();
                    }))
        .then(Mono.just(ResponseEntity.internalServerError().body("Failed to submit analysis")));
  }
}
