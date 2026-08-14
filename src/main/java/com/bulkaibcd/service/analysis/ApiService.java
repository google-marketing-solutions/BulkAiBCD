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

package com.bulkaibcd.service.analysis;

import reactor.core.publisher.Mono;

/**
 * Generic use-case interface for handling reactive API requests.
 *
 * @param <I> The input request type (e.g., DTO, Map, or Void)
 * @param <O> The output response type (e.g., ResponseEntity, DTO, or String)
 */
public interface ApiService<I, O> {
  Mono<O> execute(I request);
}
