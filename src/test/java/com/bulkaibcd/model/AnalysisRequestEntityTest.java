package com.bulkaibcd.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;

class AnalysisRequestEntityTest {

  @Test
  void builderAndGettersRoundTrip() {
    Timestamp now = Timestamp.now();
    AnalysisRequestEntity entity =
        AnalysisRequestEntity.builder()
            .analysisId("abc-123")
            .requesterId("user-1")
            .analysisName("Q3 Review")
            .analysisType("standard")
            .analysisStatus("PENDING")
            .createdAt(now)
            .updatedAt(now)
            .build();

    assertThat(entity.getAnalysisId()).isEqualTo("abc-123");
    assertThat(entity.getRequesterId()).isEqualTo("user-1");
    assertThat(entity.getAnalysisName()).isEqualTo("Q3 Review");
    assertThat(entity.getAnalysisType()).isEqualTo("standard");
    assertThat(entity.getAnalysisStatus()).isEqualTo("PENDING");
    assertThat(entity.getCreatedAt()).isEqualTo(now);
    assertThat(entity.getUpdatedAt()).isEqualTo(now);
  }

  @Test
  void equalsBasedOnAllFields() {
    Timestamp t = Timestamp.ofTimeSecondsAndNanos(1_700_000_000L, 0);
    AnalysisRequestEntity a =
        AnalysisRequestEntity.builder()
            .analysisId("a")
            .requesterId("r")
            .analysisName("n")
            .analysisType("t")
            .analysisStatus("s")
            .createdAt(t)
            .updatedAt(t)
            .build();
    AnalysisRequestEntity b =
        AnalysisRequestEntity.builder()
            .analysisId("a")
            .requesterId("r")
            .analysisName("n")
            .analysisType("t")
            .analysisStatus("s")
            .createdAt(t)
            .updatedAt(t)
            .build();

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }

  @Test
  void timestampPreservesNanosecondPrecision() {
    Timestamp t = Timestamp.ofTimeSecondsAndNanos(1_700_000_000L, 123_456_789);
    AnalysisRequestEntity entity =
        AnalysisRequestEntity.builder().analysisId("x").createdAt(t).build();

    assertThat(entity.getCreatedAt().getSeconds()).isEqualTo(1_700_000_000L);
    assertThat(entity.getCreatedAt().getNanos()).isEqualTo(123_456_789);
  }
}
