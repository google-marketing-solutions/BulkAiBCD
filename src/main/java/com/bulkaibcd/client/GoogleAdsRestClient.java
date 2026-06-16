package com.bulkaibcd.client;

import com.bulkaibcd.model.GoogleAdsCampaignDto;
import com.bulkaibcd.model.GoogleAdsVideoAssetDto;
import com.bulkaibcd.repository.GoogleAdsCredentialsRepository;
import com.bulkaibcd.service.ads.GoogleAdsTokenService;
import com.bulkaibcd.service.ads.GoogleAdsQueryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@Slf4j
@RequiredArgsConstructor
public class GoogleAdsRestClient implements GoogleAdsClient {

  private final GoogleAdsTokenService tokenService;
  private final GoogleAdsCredentialsRepository credentialsRepository;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient = HttpClient.newBuilder().build();

  private static final String GOOGLE_ADS_API_VERSION = "v17";
  private static final String ADS_BASE_URL = "https://googleads.googleapis.com/" + GOOGLE_ADS_API_VERSION;

  @Override
  public Flux<String> listAccessibleCustomers() {
    return tokenService.getValidAccessToken()
        .flatMapMany(accessToken -> credentialsRepository.findById("global")
            .flatMapMany(creds -> Mono.fromCallable(() -> {
              HttpRequest request = HttpRequest.newBuilder()
                  .uri(URI.create(ADS_BASE_URL + "/customers:listAccessibleCustomers"))
                  .header("Authorization", "Bearer " + accessToken)
                  .header("developer-token", creds.getDeveloperToken())
                  .GET()
                  .build();

              HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
              if (response.statusCode() != 200) {
                log.error("GoogleAdsRestClient: Failed to list customers. Status: {}, Body: {}", response.statusCode(), response.body());
                throw new IllegalStateException("Failed to fetch customer list: " + response.body());
              }

              JsonNode json = objectMapper.readTree(response.body());
              List<String> customers = new ArrayList<>();
              if (json.has("resourceNames")) {
                for (JsonNode node : json.get("resourceNames")) {
                  String resourceName = node.asText();
                  customers.add(resourceName.replace("customers/", ""));
                }
              }
              return customers;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(Flux::fromIterable)));
  }

  @Override
  public Flux<GoogleAdsCampaignDto> searchCampaigns(
      String customerId, String nameMatch, String matchType, String status) {
    return tokenService.getValidAccessToken()
        .flatMapMany(accessToken -> credentialsRepository.findById("global")
            .flatMapMany(creds -> Mono.fromCallable(() -> {
              String gaql = GoogleAdsQueryBuilder.buildCampaignQuery(nameMatch, matchType, status);
              ObjectNode body = objectMapper.createObjectNode();
              body.put("query", gaql);

              HttpRequest request = HttpRequest.newBuilder()
                  .uri(URI.create(ADS_BASE_URL + "/customers/" + customerId + "/googleAds:search"))
                  .header("Authorization", "Bearer " + accessToken)
                  .header("developer-token", creds.getDeveloperToken())
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                  .build();

              HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
              if (response.statusCode() != 200) {
                log.error("GoogleAdsRestClient: Search campaigns failed. Status: {}, Body: {}", response.statusCode(), response.body());
                throw new IllegalStateException("Failed to search campaigns: " + response.body());
              }

              JsonNode json = objectMapper.readTree(response.body());
              List<GoogleAdsCampaignDto> campaigns = new ArrayList<>();
              if (json.has("results")) {
                for (JsonNode row : json.get("results")) {
                  JsonNode campaign = row.get("campaign");
                  campaigns.add(GoogleAdsCampaignDto.builder()
                      .id(campaign.get("id").asText())
                      .name(campaign.get("name").asText())
                      .status(campaign.get("status").asText())
                      .build());
                }
              }
              return campaigns;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(Flux::fromIterable)));
  }

  @Override
  public Flux<GoogleAdsVideoAssetDto> listVideoAssets(String customerId, String campaignId) {
    return tokenService.getValidAccessToken()
        .flatMapMany(accessToken -> credentialsRepository.findById("global")
            .flatMapMany(creds -> Mono.fromCallable(() -> {
              String gaql = GoogleAdsQueryBuilder.buildVideoAssetQuery(campaignId);
              ObjectNode body = objectMapper.createObjectNode();
              body.put("query", gaql);

              HttpRequest request = HttpRequest.newBuilder()
                  .uri(URI.create(ADS_BASE_URL + "/customers/" + customerId + "/googleAds:search"))
                  .header("Authorization", "Bearer " + accessToken)
                  .header("developer-token", creds.getDeveloperToken())
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                  .build();

              HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
              if (response.statusCode() != 200) {
                log.error("GoogleAdsRestClient: Search video assets failed. Status: {}, Body: {}", response.statusCode(), response.body());
                throw new IllegalStateException("Failed to search video assets: " + response.body());
              }

              JsonNode json = objectMapper.readTree(response.body());
              List<GoogleAdsVideoAssetDto> assets = new ArrayList<>();
              if (json.has("results")) {
                for (JsonNode row : json.get("results")) {
                  JsonNode adNode = row.get("adGroupAd").get("ad");
                  if (adNode.has("responsiveVideoAd") && adNode.get("responsiveVideoAd").has("videos")) {
                    ArrayNode videos = (ArrayNode) adNode.get("responsiveVideoAd").get("videos");
                    for (JsonNode video : videos) {
                      if (video.has("youtubeVideoId")) {
                        String youtubeVideoId = video.get("youtubeVideoId").asText();
                        String adName = adNode.has("name") ? adNode.get("name").asText() : adNode.get("id").asText();
                        assets.add(GoogleAdsVideoAssetDto.builder()
                            .id(adNode.get("id").asText())
                            .name(adName)
                            .youtubeVideoId(youtubeVideoId)
                            .build());
                      }
                    }
                  }
                }
              }
              return assets;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(Flux::fromIterable)));
  }
}
