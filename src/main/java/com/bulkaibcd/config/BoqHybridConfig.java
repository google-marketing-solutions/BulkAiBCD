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
  
  import com.bulkaibcd.proto.InputServiceGrpc;
  import com.google.auth.oauth2.GoogleCredentials;
  import com.google.cloud.hybrid.connect.c2pauthorizer.client.C2PAuthorizerClientEnvironment;
  import com.google.cloud.hybrid.connect.c2pauthorizer.client.HelheimTokenRefresher;
  import io.grpc.CallCredentials;
  import io.grpc.CompositeCallCredentials;
  import io.grpc.ManagedChannel;
  import io.grpc.ManagedChannelBuilder;
  import io.grpc.auth.MoreCallCredentials;
  import java.util.Collections;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.beans.factory.annotation.Qualifier;
  import org.springframework.beans.factory.annotation.Value;
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;
  
  @Configuration
  @Slf4j
  public class BoqHybridConfig {
  
    @Bean
    public HelheimTokenRefresher helheimTokenRefresher(
        @Value("${app.boq.hybrid-api-service-name:staging-bulkaibcd.hybrid.googleapis.com}")
            String serviceName) {
      try {
        log.info("Initializing HelheimTokenRefresher for service: {}", serviceName);
        return HelheimTokenRefresher.builder()
            .setServiceName(serviceName)
            .setEnvironment(C2PAuthorizerClientEnvironment.STAGING)
            .build();
      } catch (Exception e) {
        log.warn("Could not initialize HelheimTokenRefresher bean: {}", e.getMessage());
        return null;
      }
    }
  
    @Bean("boqGrpcManagedChannel")
    public ManagedChannel boqGrpcManagedChannel(
        @Value("${app.boq.hybrid-api-url:https://autopush-bulkaibcd.hybrid.sandbox.googleapis.com}")
            String rawUrl) {
      String host = rawUrl.replace("https://", "").replace("http://", "").split("/")[0];
      int port = 443;
      if (host.contains(":")) {
        String[] parts = host.split(":");
        host = parts[0];
        port = Integer.parseInt(parts[1]);
      }
      return ManagedChannelBuilder.forAddress(host, port)
          .useTransportSecurity()
          .build();
    }
  
    @Bean("boqCompositeCallCredentials")
    public CallCredentials boqCompositeCallCredentials(
        @Autowired(required = false) HelheimTokenRefresher helheimTokenRefresher) {
      try {
        GoogleCredentials credentials =
            GoogleCredentials.getApplicationDefault()
                .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"));
        CallCredentials oauthCreds = MoreCallCredentials.from(credentials);
  
        if (helheimTokenRefresher != null) {
          CallCredentials helheimCreds = helheimTokenRefresher.getHelheimTokenCallCredentials();
          return new CompositeCallCredentials(oauthCreds, helheimCreds);
        }
        return oauthCreds;
      } catch (Exception e) {
        log.warn("Could not create gRPC CompositeCallCredentials: {}", e.getMessage());
        return null;
      }
    }
  
    @Bean
    public InputServiceGrpc.InputServiceBlockingStub inputServiceBlockingStub(
        @Qualifier("boqGrpcManagedChannel") ManagedChannel boqGrpcManagedChannel,
        @Autowired(required = false) @Qualifier("boqCompositeCallCredentials") CallCredentials boqCompositeCallCredentials) {
      InputServiceGrpc.InputServiceBlockingStub stub =
          InputServiceGrpc.newBlockingStub(boqGrpcManagedChannel);
      if (boqCompositeCallCredentials != null) {
        stub = stub.withCallCredentials(boqCompositeCallCredentials);
      }
      return stub;
    }
  }
  
  