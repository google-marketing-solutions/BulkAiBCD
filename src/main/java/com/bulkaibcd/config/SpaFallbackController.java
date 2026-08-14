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

package com.bulkaibcd.config;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * SPA fallback. When the user refreshes /list (or any client-side route), Spring's static-resource
 * handler returns 404 because no /list file exists. The default ErrorController then renders the
 * Whitelabel error page. We override that here: for HTML requests on non-API paths that 404, we
 * forward to /index.html so the Angular router can take over. JSON / API errors fall through to a
 * normal JSON 404 response.
 */
@Controller
public class SpaFallbackController implements ErrorController {

  @RequestMapping("/error")
  public Object error(HttpServletRequest request) {
    Object statusObj = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int status = statusObj == null ? 500 : Integer.parseInt(statusObj.toString());

    String path = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    String accept = request.getHeader("Accept");
    boolean wantsHtml = accept != null && accept.contains("text/html");
    boolean isApi = path != null && (path.startsWith("/api/") || path.startsWith("/actuator/"));

    if (status == HttpStatus.NOT_FOUND.value() && wantsHtml && !isApi) {
      return "forward:/index.html";
    }
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"status\":" + status + ",\"path\":\"" + (path == null ? "" : path) + "\"}");
  }
}
