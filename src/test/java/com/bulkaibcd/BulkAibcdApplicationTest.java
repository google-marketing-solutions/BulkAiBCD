package com.bulkaibcd;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.internal.hybridconnect.c2pauthorizer.v1.GetHelheimTokenRequest;
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

  @Test
  void protobufRuntimeVersionLoadsSuccessfully() {
    GetHelheimTokenRequest request = GetHelheimTokenRequest.newBuilder()
        .setServiceName("staging-bulkaibcd.hybrid.googleapis.com")
        .build();
    assertThat(request).isNotNull();
    assertThat(request.getServiceName()).isEqualTo("staging-bulkaibcd.hybrid.googleapis.com");
  }
}
