package com.bulkaibcd.client;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.FileData;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import java.io.IOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Client gateway responsible for encapsulating Vertex AI GenerativeModel operations. */
@Component
@Slf4j
@RequiredArgsConstructor
public class GeminiClient {

  @Value("${google.cloud.project.id}")
  private String projectId;

  @Value("${google.cloud.location:us-central1}")
  private String location;

  @Value("${google.cloud.model.name:gemini-2.5-pro}")
  private String modelName;

  private static final String VIDEO_MIME_TYPE = "video/mp4";
  private static final float TEMPERATURE = 0.1f;

  public String callGemini(String videoUri, String prompt, Optional<String> responseMimeType)
      throws IOException {
    try (VertexAI vertexAI = new VertexAI(projectId, location)) {
      GenerationConfig.Builder configBuilder =
          GenerationConfig.newBuilder().setTemperature(TEMPERATURE);

      responseMimeType.ifPresent(configBuilder::setResponseMimeType);

      GenerativeModel model =
          new GenerativeModel(modelName, vertexAI).withGenerationConfig(configBuilder.build());

      Content content =
          Content.newBuilder()
              .setRole("user")
              .addParts(
                  Part.newBuilder()
                      .setFileData(
                          FileData.newBuilder().setFileUri(videoUri).setMimeType(VIDEO_MIME_TYPE)))
              .addParts(Part.newBuilder().setText(prompt))
              .build();

      GenerateContentResponse response = model.generateContent(content);
      return ResponseHandler.getText(response).strip();
    }
  }
}
