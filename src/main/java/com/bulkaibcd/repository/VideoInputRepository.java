package com.bulkaibcd.repository;

import com.bulkaibcd.model.VideoInputEntity;
import com.google.cloud.spring.data.firestore.FirestoreReactiveRepository;
import reactor.core.publisher.Flux;

public interface VideoInputRepository extends FirestoreReactiveRepository<VideoInputEntity> {
  Flux<VideoInputEntity> findByAnalysisId(String analysisId);
}
