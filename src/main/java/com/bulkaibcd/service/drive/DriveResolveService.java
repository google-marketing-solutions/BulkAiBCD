package com.bulkaibcd.service.drive;

import com.bulkaibcd.client.GoogleDriveClient;
import com.bulkaibcd.service.analysis.ApiService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service responsible for expanding a Google Drive URL (file or folder) into a list of video
 * entries.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DriveResolveService implements ApiService<Map<String, String>, ResponseEntity<?>> {

  private final ObjectProvider<GoogleDriveClient> driveIngestServiceProvider;

  /**
   * Executes the Drive resolution workflow.
   *
   * @param body Map containing the 'url' parameter representing the Drive file or folder
   * @return A reactive {@link Mono} emitting the HTTP response with resolved video entries or an
   *     error
   */
  @Override
  public Mono<ResponseEntity<?>> execute(Map<String, String> body) {
    String url = body == null ? null : body.get("url");
    if (url == null || url.isBlank()) {
      return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "url is required")));
    }
    GoogleDriveClient svc = driveIngestServiceProvider.getIfAvailable();
    if (svc == null) {
      return Mono.just(
          ResponseEntity.status(503).body(Map.of("error", "Drive ingest service unavailable")));
    }
    return Mono.fromCallable(() -> svc.resolve(url))
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
        .<ResponseEntity<?>>map(
            resolved ->
                ResponseEntity.ok(
                    Map.of(
                        "videos",
                        resolved.stream()
                            .map(
                                resolvedFile ->
                                    Map.of(
                                        "videoName",
                                        resolvedFile.name() == null ? "" : resolvedFile.name(),
                                        "videoUrl",
                                        resolvedFile.webViewLink() == null
                                            ? url
                                            : resolvedFile.webViewLink(),
                                        "thumbnailUrl",
                                        resolvedFile.thumbnailLink() == null
                                            ? ""
                                            : resolvedFile.thumbnailLink()))
                            .toList())))
        .onErrorResume(
            err -> {
              log.warn("Drive resolve failed for {}: {}", url, err.getMessage());
              int status;
              String code;
              if (err instanceof IllegalArgumentException) {
                status = 400;
                code = "UNRECOGNIZED_URL";
              } else if (isDrivePermissionDenied(err)) {
                status = 403;
                code = "ACCESS_DENIED";
              } else {
                status = 502;
                code = "DRIVE_ERROR";
              }
              return Mono.just(
                  ResponseEntity.status(status)
                      .body(
                          Map.of(
                              "code",
                              code,
                              "error",
                              err.getMessage() == null ? "Drive error" : err.getMessage())));
            });
  }

  private static boolean isDrivePermissionDenied(Throwable err) {
    String cls = err.getClass().getSimpleName();
    if ("GoogleJsonResponseException".equals(cls)) {
      String msg = err.getMessage() == null ? "" : err.getMessage();
      return msg.startsWith("403 ") || msg.startsWith("404 ");
    }
    return false;
  }
}
