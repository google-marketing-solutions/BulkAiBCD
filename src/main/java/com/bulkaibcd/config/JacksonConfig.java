package com.bulkaibcd.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.cloud.Timestamp;
import java.io.IOException;
import java.time.Instant;
import org.springframework.boot.jackson.JsonComponent;

/**
 * Make {@code com.google.cloud.Timestamp} cross the wire as a plain ISO-8601
 * string instead of the default {seconds, nanos} object so browsers can call
 * {@code new Date(str)} without a custom parser.
 */
@JsonComponent
public class JacksonConfig extends SimpleModule {

  public JacksonConfig() {
    addSerializer(Timestamp.class, new TimestampToIsoSerializer());
    addDeserializer(Timestamp.class, new TimestampFromIsoDeserializer());
  }

  private static class TimestampToIsoSerializer extends JsonSerializer<Timestamp> {
    @Override
    public void serialize(Timestamp value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      if (value == null) {
        gen.writeNull();
        return;
      }
      Instant i = Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
      gen.writeString(i.toString());
    }
  }

  private static class TimestampFromIsoDeserializer extends JsonDeserializer<Timestamp> {
    @Override
    public Timestamp deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      String text = p.getValueAsString();
      if (text == null || text.isBlank()) return null;
      Instant i = Instant.parse(text);
      return Timestamp.ofTimeSecondsAndNanos(i.getEpochSecond(), i.getNano());
    }
  }
}
