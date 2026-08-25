package com.bulkaibcd.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JacksonConfigTest {

  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    mapper.registerModule(new JacksonConfig());
  }

  static class TimestampHolder {
    public Timestamp timestamp;

    public TimestampHolder() {}

    public TimestampHolder(Timestamp timestamp) {
      this.timestamp = timestamp;
    }
  }

  @Test
  void serializesTimestampToIsoString() throws Exception {
    Timestamp ts = Timestamp.ofTimeSecondsAndNanos(1700000000L, 500000000);
    String json = mapper.writeValueAsString(new TimestampHolder(ts));
    assertThat(json).isEqualTo("{\"timestamp\":\"2023-11-14T22:13:20.500Z\"}");
  }

  @Test
  void serializesNullTimestampToNull() throws Exception {
    String json = mapper.writeValueAsString(new TimestampHolder(null));
    assertThat(json).isEqualTo("{\"timestamp\":null}");
  }

  @Test
  void deserializesIsoStringToTimestamp() throws Exception {
    String json = "{\"timestamp\":\"2023-11-14T22:13:20.500Z\"}";
    TimestampHolder holder = mapper.readValue(json, TimestampHolder.class);
    assertThat(holder.timestamp).isNotNull();
    assertThat(holder.timestamp.getSeconds()).isEqualTo(1700000000L);
    assertThat(holder.timestamp.getNanos()).isEqualTo(500000000);
  }

  @Test
  void deserializesNullOrEmptyToNull() throws Exception {
    String jsonNull = "{\"timestamp\":null}";
    TimestampHolder holderNull = mapper.readValue(jsonNull, TimestampHolder.class);
    assertThat(holderNull.timestamp).isNull();

    String jsonBlank = "{\"timestamp\":\"   \"}";
    TimestampHolder holderBlank = mapper.readValue(jsonBlank, TimestampHolder.class);
    assertThat(holderBlank.timestamp).isNull();
  }
}

