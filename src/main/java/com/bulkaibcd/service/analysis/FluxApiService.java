package com.bulkaibcd.service.analysis;

import reactor.core.publisher.Flux;

/**
 * Generic use-case interface for handling reactive API requests that emit a stream of 0..N items.
 *
 * @param <I> The input request type (e.g., DTO, String, or Void)
 * @param <O> The output response element type (e.g., Entity or DTO)
 */
public interface FluxApiService<I, O> {
  Flux<O> execute(I request);
}
