/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bulkaibcd.enums;

/** Enumeration representing the 14 structural raw metadata categories extracted during Phase 1. */
public enum RawMetadataType {
  BRAND,
  PRODUCT,
  LANGUAGE,
  VERTICAL,
  ASSET_NAME,
  SPEECH_TRANSCRIPTION,
  TEXT_DETECTION,
  SHOT_CHANGE_DETECTION,
  LOGO_RECOGNITION,
  OBJECT_TRACKING,
  FACE_DETECTION,
  PERSON_DETECTION,
  LABEL_DETECTION,
  EXPLICIT_CONTENT_DETECTION
}
