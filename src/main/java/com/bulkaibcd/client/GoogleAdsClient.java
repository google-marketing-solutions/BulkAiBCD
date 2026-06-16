package com.bulkaibcd.client;

import com.bulkaibcd.model.GoogleAdsCampaignDto;
import com.bulkaibcd.model.GoogleAdsVideoAssetDto;
import reactor.core.publisher.Flux;

public interface GoogleAdsClient {
  Flux<String> listAccessibleCustomers();
  Flux<GoogleAdsCampaignDto> searchCampaigns(
      String customerId, String nameMatch, String matchType, String status);
  Flux<GoogleAdsVideoAssetDto> listVideoAssets(String customerId, String campaignId);
}
