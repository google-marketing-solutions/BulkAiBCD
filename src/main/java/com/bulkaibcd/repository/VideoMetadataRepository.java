package com.bulkaibcd.repository;

import com.bulkaibcd.model.VideoMetadataEntity;
import com.google.cloud.spring.data.firestore.FirestoreReactiveRepository;
import reactor.core.publisher.Flux;

public interface VideoMetadataRepository extends FirestoreReactiveRepository<VideoMetadataEntity> {
  Flux<VideoMetadataEntity> findByAnalysisId(String analysisId);
}
