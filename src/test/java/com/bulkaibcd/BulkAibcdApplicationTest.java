package com.bulkaibcd;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BulkAibcdApplicationTest {

  @Test
  void applicationClassInstantiates() {
    BulkAibcdApplication app = new BulkAibcdApplication();
    assertThat(app).isNotNull();
  }
}

