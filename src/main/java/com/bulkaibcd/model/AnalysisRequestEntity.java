package com.bulkaibcd.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.spring.data.firestore.Document;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import java.util.List;
import lombok.NoArgsConstructor;

@Document(collectionName = "analyses")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisRequestEntity {
  @DocumentId private String analysisId;
  private String requesterId;
  private String analysisName;
  private String analysisType;
  private String analysisStatus;
  private String brandName;
  private String marketingObjective;
  private List<String> customFeatures;
  private Timestamp createdAt;
  private Timestamp updatedAt;
}
