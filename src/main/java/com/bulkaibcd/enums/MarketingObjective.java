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

/** Enumeration representing the primary advertiser marketing objective for an analysis run. */
public enum MarketingObjective {
  /** Core / Unknown objective (default fallback) */
  CORE_UNKNOWN,

  /** Brand Awareness campaign objective */
  AWARENESS,

  /** Product Consideration campaign objective */
  CONSIDERATION,

  /** Direct Action campaign objective */
  ACTION
}
