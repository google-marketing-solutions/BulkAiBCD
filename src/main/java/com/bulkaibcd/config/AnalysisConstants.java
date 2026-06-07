package com.bulkaibcd.config;

import java.util.List;
import java.util.Map;

public final class AnalysisConstants {

  public static final String BRAND_PROMPT =
      "Analyze the video and identify the primary brand name being advertised. If no brand is"
          + " clear, respond with 'The brand'. Respond with ONLY the brand name.";

  public static final String PRODUCT_PROMPT =
      "Analyze the video and identify the specific products or services shown, mentioned, or"
          + " clearly implied. If no specific products are clear, respond with 'The product'."
          + " Respond with ONLY the product(s) as a comma-separated list of names. Do NOT use JSON.";

  public static final String LANGUAGE_PROMPT =
      "Analyze the video and identify the primary spoken language. Respond with ONLY the BCP-47"
          + " language code (e.g., en-US, es-ES, fr-FR, ja-JP). If there is no speech, respond with 'zxx'.";

  public static final String VERTICAL_PROMPT =
      "Analyze the video and identify the industry vertical for the brand and product (e.g., CPG,"
          + " Automotive, Technology, Finance, Retail). If unknown, respond with 'The vertical'."
          + " Respond with ONLY the vertical.";

  public static final String ASSET_NAME_PROMPT =
      "Analyze the video and create a short, descriptive title for it, like a professional creative"
          + " director would. The title should include brand, product, and key theme or action, but"
          + " not the video duration. Example: 'Old Spice Fiji | The Man Your Man Could Smell"
          + " Like'. If unknown, respond with 'The video'. Respond with ONLY the title as a plain string."
          + " Do NOT use JSON.";

  public static final String SPEECH_TRANSCRIPTION_PROMPT =
      "Analyze the video to first identify the language spoken, the primary brand name, and the"
          + " product being advertised. Use the identified brand and product names to bias and"
          + " improve the accuracy of the speech-to-text conversion. Transcribe all spoken words in"
          + " the video to text. Pay close attention to the brand and product names mentioned.\n"
          + "\n"
          + "-- INSTRUCTIONS --\n"
          + "Only transcribe audio speech; do not transcribe text that appears"
          + " visually in the video frame but is not spoken. Provide the result as a JSON array,"
          + " where each element is an object with 'transcript', 'confidence', 'start_time', and"
          + " 'end_time' in minutes.seconds format where seconds are zero-padded to two digits"
          + " (e.g., 10 seconds=0.10, 1 second=0.01, 3 minutes 5 seconds=3.05, 2 minutes 30 seconds=2.30)"
          + " for each speech segment detected. Example: [{'transcript': 'hello', 'confidence': 0.9,"
          + " 'start_time': 0.01, 'end_time': 0.02}]. If no speech is detected, return an empty"
          + " array. Respond ONLY with JSON.";

  public static final String TEXT_DETECTION_PROMPT =
      "Detect and list all text snippets visible in the video. Provide the result as a JSON array,"
          + " where each element represents a single, contiguous time segment when text is visible."
          + " Each entry should contain 'text', 'confidence', 'start_time', and 'end_time' in"
          + " minutes.seconds format where seconds are zero-padded to two digits (e.g., 10"
          + " seconds=0.10, 1 second=0.01, 3 minutes 5 seconds=3.05, 2 minutes 30 seconds=2.30)."
          + " If the same text disappears and reappears, create separate entries for each"
          + " appearance. Example: [{'text': 'SALE', 'confidence': 0.95, 'start_time': 0.03,"
          + " 'end_time': 0.05}, {'text': 'SALE', 'confidence': 0.95, 'start_time': 1.10,"
          + " 'end_time': 1.12}]. If no text is detected, return an empty array. Respond ONLY with JSON.";

  public static final String SHOT_CHANGE_DETECTION_PROMPT =
      "Detect all shot changes in the video. Provide the result as a JSON array, where each"
          + " element is an object containing 'start_time' and 'end_time' in minutes.seconds"
          + " format where seconds are zero-padded to two digits (e.g., 10 seconds=0.10, 1"
          + " second=0.01, 3 minutes 5 seconds=3.05, 2 minutes 30 seconds=2.30) for each shot."
          + " Example: [{'start_time': 0.00, 'end_time': 0.04}, {'start_time': 0.04, 'end_time': 0.10}]. If"
          + " no shot changes are detected, return an empty array. Respond ONLY with JSON.";

  public static final String LOGO_RECOGNITION_PROMPT =
      "Detect and list all brand logos visible in the video. Provide the result as a JSON array,"
          + " where each element represents a single, contiguous time segment when a logo is"
          + " visible. Each entry should contain 'logo_description', 'confidence', 'start_time',"
          + " and 'end_time' in minutes.seconds format where seconds are zero-padded to two digits"
          + " (e.g., 10 seconds=0.10, 1 second=0.01, 3 minutes 5 seconds=3.05, 2 minutes 30"
          + " seconds=2.30). If the same logo disappears and reappears, create separate entries"
          + " for each appearance. Example: [{'logo_description': 'Nike', 'confidence':"
          + " 0.8, 'start_time': 0.05, 'end_time': 0.07}, {'logo_description': 'Nike', 'confidence'"
          + " : 0.8, 'start_time': 0.45, 'end_time': 0.57}]. If"
          + " no logos are detected, return an empty array. Respond ONLY with JSON.";

  public static final String OBJECT_TRACKING_PROMPT =
      "Detect prominent objects in the video. Provide the result as a JSON array, where each"
          + " element represents a single, contiguous time segment when an object is visible. Each"
          + " entry should contain 'object_description', 'confidence', 'start_time', and"
          + " 'end_time' in minutes.seconds format where seconds are zero-padded to two digits"
          + " (e.g., 10 seconds=0.10, 1 second=0.01, 3 minutes 5 seconds=3.05, 2 minutes 30"
          + " seconds=2.30), and a 'frames' array. Each element in 'frames' should contain a 'box'"
          + " object with keys 'l', 'r', 't', 'b' representing the normalized bounding box"
          + " coordinates (left, right, top, bottom) for that frame. The 'object_description'"
          + " should be short (1-3 words). If the same object disappears and reappears, create"
          + " separate entries for each appearance. Example: [{'object_description': 'Red car',"
          + " 'confidence': 0.7, 'start_time': 0.10, 'end_time': 0.15, 'frames': [{'box': {'l':"
          + " 0.1, 'r': 0.2, 't': 0.3, 'b': 0.4}}]}, {'object_description': 'Red car',"
          + " 'confidence': 0.7, 'start_time': 0.40, 'end_time': 0.55, 'frames': [{'box': {'l':"
          + " 0.1, 'r': 0.2, 't': 0.3, 'b': 0.4}}]}]. If no objects are detected, return an empty"
          + " array. Respond ONLY with JSON.";

  public static final String FACE_DETECTION_PROMPT =
      "Detect all human face occurrences in the video. Provide the result as a JSON array, where"
          + " each element represents a single, contiguous time segment when a face is visible."
          + " Each entry should contain 'confidence', 'start_time', and 'end_time' in"
          + " minutes.seconds format where seconds are zero-padded to two digits (e.g., 10"
          + " seconds=0.10, 1 second=0.01, 3 minutes 5 seconds=3.05, 2 minutes 30 seconds=2.30),"
          + " and a 'frames' array. Each element in 'frames' should contain a 'box' object with"
          + " keys 'l', 'r', 't', 'b' representing the normalized bounding box coordinates (left,"
          + " right, top, bottom) for that frame. If the same face disappears and reappears,"
          + " create separate entries for each appearance. Example: [{'confidence': 0.99,"
          + " 'start_time': 0.01, 'end_time': 0.03, 'frames': [{'box': {'l': 0.1, 'r': 0.2, 't':"
          + " 0.3, 'b': 0.4}}]}]. If no faces are detected, return an empty array. Respond ONLY with JSON.";

  public static final String PERSON_DETECTION_PROMPT =
      "Detect all human person occurrences in the video. Provide the result as a JSON array,"
          + " where each element represents a single, contiguous time segment when a person is"
          + " visible. Each entry should contain 'confidence', 'start_time', and 'end_time' in"
          + " minutes.seconds format where seconds are zero-padded to two digits (e.g., 10"
          + " seconds=0.10, 1 second=0.01, 3 minutes 5 seconds=3.05, 2 minutes 30 seconds=2.30),"
          + " and a 'frames' array. Each element in 'frames' should contain a 'box' object with"
          + " keys 'l', 'r', 't', 'b' representing the normalized bounding box coordinates (left,"
          + " right, top, bottom) for that frame. If the same person disappears and"
          + " reappears, create separate entries for each appearance. Example: [{'confidence': 0.9,"
          + " 'start_time': 0.01, 'end_time': 0.04, 'frames': [{'box': {'l': 0.1, 'r': 0.2, 't':"
          + " 0.3, 'b': 0.4}}]}]. If no people are detected, return an empty array. Respond ONLY with JSON.";

  public static final String LABEL_DETECTION_PROMPT =
      "Detect and list labels for entities (e.g., objects, scenes, activities) in the video."
          + " Provide the result as a JSON array, where each element represents a single,"
          + " contiguous time segment when an entity is visible. Each entry should contain"
          + " 'label', 'confidence', 'start_time', and 'end_time' in minutes.seconds format"
          + " where seconds are zero-padded to two digits (e.g., 10 seconds=0.10, 1 second=0.01,"
          + " 3 minutes 5 seconds=3.05, 2 minutes 30 seconds=2.30), and a 'frames' array. Each"
          + " element in 'frames' should contain a 'box' object with keys 'l', 'r', 't', 'b'"
          + " representing the normalized bounding box coordinates (left, right, top, bottom) for"
          + " that frame if the label refers to an object with spatial extent. If the same"
          + " entity disappears and reappears, create separate entries for each appearance."
          + " Example: [{'label': 'Dog', 'confidence': 0.85, 'start_time': 0.02, 'end_time':"
          + " 0.04, 'frames': [{'box': {'l': 0.1, 'r': 0.2, 't': 0.3, 'b': 0.4}}]},"
          + " {'label': 'Dog', 'confidence': 0.85, 'start_time': 0.08, 'end_time': 1.01,"
          + " 'frames': [{'box': {'l': 0.1, 'r': 0.2, 't': 0.3, 'b': 0.4}}]}]. If no labels are"
          + " detected, return an empty array. Respond ONLY with JSON.";

  public static final String EXPLICIT_CONTENT_DETECTION_PROMPT =
      "Detect if this video contains explicit content (e.g., nudity, violence, inappropriate"
          + " language). Provide the result as a JSON array where each element represents a"
          + " segment containing explicit content, with fields 'category', 'likelihood',"
          + " 'start_time' and 'end_time' in minutes.seconds format where seconds are zero-padded"
          + " to two digits (e.g., 10 seconds=0.10, 1 second=0.01, 3 minutes 5 seconds=3.05, 2"
          + " minutes 30 seconds=2.30). If no explicit content is detected, return an empty array."
          + " Respond ONLY with JSON.";

  public static final String SINGLE_FEATURE_PROMPT_TEMPLATE =
      "You are a meticulous and objective video analyst. Your primary task is to analyze the provided video file to determine if it contains the feature: %s.\n"
          + "\n"
          + "--- CRITICAL INSTRUCTIONS ---\n"
          + "Your primary task is to analyze the raw video file directly.\n"
          + "You have also been provided with a pre-analyzed metadata summary below. Use this summary as a strong HINT and for cross-verification.\n"
          + "HOWEVER, IF THE METADATA SUMMARY IS EMPTY, INCOMPLETE, OR CONTRADICTS YOUR DIRECT ANALYSIS OF THE VIDEO, YOU MUST PRIORITIZE YOUR OWN FRAME-BY-FRAME VISUAL AND AUDIO ANALYSIS TO MAKE THE FINAL DECISION. The raw video is the ultimate source of truth.\n"
          + "\n"
          + "--- CONTEXT ---\n"
          + "Asset Name: %s\n"
          + "Brand: %s\n"
          + "Product(s): %s\n"
          + "Vertical: %s\n"
          + "Video Language: %s\n"
          + "\n"
          + "--- FEATURE DEFINITION ---\n"
          + "Criteria: %s\n"
          + "\n"
          + "--- EVIDENCE FROM PRE-ANALYSIS (Use as a Hint) ---\n"
          + "%s\n"
          + "\n"
          + "--- YOUR TASK ---\n"
          + "Based on your direct analysis of the video (as the primary source) and the provided evidence, provide your findings in the required JSON format. Your entire response, including all text, must be in English.\n"
          + "%s\n"
          + "CRITICAL: Your 'rationale' must state whether you relied on the metadata or on direct video analysis, especially if they contradicted each other.";

  public static final List<String> METADATA_TYPES =
      List.of(
          "BRAND",
          "PRODUCT",
          "LANGUAGE",
          "VERTICAL",
          "ASSET_NAME",
          "SPEECH_TRANSCRIPTION",
          "TEXT_DETECTION",
          "SHOT_CHANGE_DETECTION",
          "LOGO_RECOGNITION",
          "OBJECT_TRACKING",
          "FACE_DETECTION",
          "PERSON_DETECTION",
          "LABEL_DETECTION",
          "EXPLICIT_CONTENT_DETECTION");

  public static final Map<String, String> RAW_PROMPT_MAP =
      Map.ofEntries(
          Map.entry("BRAND", BRAND_PROMPT),
          Map.entry("PRODUCT", PRODUCT_PROMPT),
          Map.entry("LANGUAGE", LANGUAGE_PROMPT),
          Map.entry("VERTICAL", VERTICAL_PROMPT),
          Map.entry("ASSET_NAME", ASSET_NAME_PROMPT),
          Map.entry("SPEECH_TRANSCRIPTION", SPEECH_TRANSCRIPTION_PROMPT),
          Map.entry("TEXT_DETECTION", TEXT_DETECTION_PROMPT),
          Map.entry("SHOT_CHANGE_DETECTION", SHOT_CHANGE_DETECTION_PROMPT),
          Map.entry("LOGO_RECOGNITION", LOGO_RECOGNITION_PROMPT),
          Map.entry("OBJECT_TRACKING", OBJECT_TRACKING_PROMPT),
          Map.entry("FACE_DETECTION", FACE_DETECTION_PROMPT),
          Map.entry("PERSON_DETECTION", PERSON_DETECTION_PROMPT),
          Map.entry("LABEL_DETECTION", LABEL_DETECTION_PROMPT),
          Map.entry("EXPLICIT_CONTENT_DETECTION", EXPLICIT_CONTENT_DETECTION_PROMPT));

  private AnalysisConstants() {}
}
