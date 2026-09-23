package com.bulkaibcd;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.Test;

class BulkAibcdApplicationTest {

  @Test
  void applicationClassInstantiates() {
    BulkAibcdApplication app = new BulkAibcdApplication();
    assertThat(app).isNotNull();
  }

  @Test
  void managedChannelInstantiatesSuccessfully() {
    ManagedChannel channel = ManagedChannelBuilder.forTarget("firestore.googleapis.com:443")
        .usePlaintext()
        .build();
    try {
      assertThat(channel).isNotNull();
    } finally {
      channel.shutdownNow();
    }
  }

}
