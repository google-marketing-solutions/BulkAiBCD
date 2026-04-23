package com.bulkaibcd.repository;

import com.bulkaibcd.model.AnalysisRequestEntity;
import com.google.cloud.spring.data.firestore.FirestoreReactiveRepository;
import reactor.core.publisher.Flux;

public interface AnalysisRequestRepository
    extends FirestoreReactiveRepository<AnalysisRequestEntity> {
  Flux<AnalysisRequestEntity> findByRequesterIdOrderByCreatedAtDesc(String requesterId);
}
