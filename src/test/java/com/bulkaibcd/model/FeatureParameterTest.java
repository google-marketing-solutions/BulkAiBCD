package com.bulkaibcd.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureParameterTest {

  @Test
  void testFeatureParameter() {
    FeatureParameter fp =
        FeatureParameter.builder()
            .id("a_first_5_secs")
            .name("Brand in First 5 Seconds")
            .category("Attract")
            .criteria("Look for brand logo or mention in the first 5s")
            .promptTemplate("Is brand present in first 5s?")
            .type("STANDARD")
            .supportedFormats(List.of("LONG", "SHORT"))
            .build();

    assertThat(fp.getId()).isEqualTo("a_first_5_secs");
    assertThat(fp.getName()).isEqualTo("Brand in First 5 Seconds");
    assertThat(fp.getCategory()).isEqualTo("Attract");
    assertThat(fp.getCriteria()).isEqualTo("Look for brand logo or mention in the first 5s");
    assertThat(fp.getPromptTemplate()).isEqualTo("Is brand present in first 5s?");
    assertThat(fp.getType()).isEqualTo("STANDARD");
    assertThat(fp.getSupportedFormats()).containsExactly("LONG", "SHORT");
  }
}

