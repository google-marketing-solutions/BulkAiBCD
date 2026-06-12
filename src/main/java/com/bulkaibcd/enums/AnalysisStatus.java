package com.bulkaibcd.enums;

/**
 * Enumeration representing the lifecycle states of an analysis run and its associated video assets.
 */
public enum AnalysisStatus {
  PENDING,
  PROCESSING,
  METADATA_EXTRACTED,
  BATCH_QUEUED,
  COMPLETED,
  FAILED,
  CANCELLED,
  DELETED
}
