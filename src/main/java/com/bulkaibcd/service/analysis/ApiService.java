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
