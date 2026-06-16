package com.bulkaibcd.repository;

import com.bulkaibcd.model.GoogleAdsCredentialsEntity;
import com.google.cloud.spring.data.firestore.FirestoreReactiveRepository;

public interface GoogleAdsCredentialsRepository
    extends FirestoreReactiveRepository<GoogleAdsCredentialsEntity> {}
