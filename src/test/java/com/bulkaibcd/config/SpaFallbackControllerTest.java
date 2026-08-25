package com.bulkaibcd.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SpaFallbackControllerTest {

  private SpaFallbackController controller;
  private HttpServletRequest request;

  @BeforeEach
  void setUp() {
    controller = new SpaFallbackController();
    request = mock(HttpServletRequest.class);
  }

  @Test
  void html404OnNonApiPathForwardsToIndexHtml() {
    when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(404);
    when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn("/dashboard");
    when(request.getHeader("Accept")).thenReturn("text/html,application/xhtml+xml");

    Object result = controller.error(request);
    assertThat(result).isEqualTo("forward:/index.html");
  }

  @Test
  void json404OnNonApiPathReturnsJsonResponseEntity() {
    when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(404);
    when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn("/some-resource");
    when(request.getHeader("Accept")).thenReturn("application/json");

    Object result = controller.error(request);
    assertThat(result).isInstanceOf(ResponseEntity.class);
    @SuppressWarnings("unchecked")
    ResponseEntity<String> response = (ResponseEntity<String>) result;
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).contains("\"status\":404");
    assertThat(response.getBody()).contains("\"path\":\"/some-resource\"");
  }

  @Test
  void html404OnApiPathReturnsJsonResponseEntity() {
    when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(404);
    when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn("/api/v2/unknown");
    when(request.getHeader("Accept")).thenReturn("text/html");

    Object result = controller.error(request);
    assertThat(result).isInstanceOf(ResponseEntity.class);
    @SuppressWarnings("unchecked")
    ResponseEntity<String> response = (ResponseEntity<String>) result;
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody()).contains("\"path\":\"/api/v2/unknown\"");
  }

  @Test
  void non404ReturnsJsonErrorWithOriginalStatus() {
    when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(500);
    when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn("/api/v2/input/submit");
    when(request.getHeader("Accept")).thenReturn("text/html");

    Object result = controller.error(request);
    assertThat(result).isInstanceOf(ResponseEntity.class);
    @SuppressWarnings("unchecked")
    ResponseEntity<String> response = (ResponseEntity<String>) result;
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).contains("\"status\":500");
  }

  @Test
  void nullStatusCodeDefaultsTo500() {
    when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(null);
    when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn(null);
    when(request.getHeader("Accept")).thenReturn(null);

    Object result = controller.error(request);
    assertThat(result).isInstanceOf(ResponseEntity.class);
    @SuppressWarnings("unchecked")
    ResponseEntity<String> response = (ResponseEntity<String>) result;
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).contains("\"status\":500");
    assertThat(response.getBody()).contains("\"path\":\"\"");
  }
}

