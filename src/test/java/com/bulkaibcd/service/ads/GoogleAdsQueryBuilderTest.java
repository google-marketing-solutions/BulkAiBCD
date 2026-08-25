package com.bulkaibcd.service.ads;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoogleAdsQueryBuilderTest {

  @Test
  void buildCampaignQueryActiveOnly() {
    String query = GoogleAdsQueryBuilder.buildCampaignQuery(null, null, "active");
    assertThat(query).isEqualTo("SELECT campaign.id, campaign.name, campaign.status FROM campaign WHERE campaign.status = 'ENABLED'");
  }

  @Test
  void buildCampaignQueryAllStatuses() {
    String query = GoogleAdsQueryBuilder.buildCampaignQuery(null, null, "all");
    assertThat(query).isEqualTo("SELECT campaign.id, campaign.name, campaign.status FROM campaign WHERE campaign.status IN ('ENABLED', 'PAUSED')");
  }

  @Test
  void buildCampaignQueryWithContainsMatch() {
    String query = GoogleAdsQueryBuilder.buildCampaignQuery("Promo's", "contains", "active");
    assertThat(query).isEqualTo("SELECT campaign.id, campaign.name, campaign.status FROM campaign WHERE campaign.status = 'ENABLED' AND campaign.name LIKE '%Promo\\'s%'");
  }

  @Test
  void buildCampaignQueryWithStartsWithMatch() {
    String query = GoogleAdsQueryBuilder.buildCampaignQuery("Summer", "startsWith", "active");
    assertThat(query).isEqualTo("SELECT campaign.id, campaign.name, campaign.status FROM campaign WHERE campaign.status = 'ENABLED' AND campaign.name LIKE 'Summer%'");
  }

  @Test
  void buildCampaignQueryWithEndsWithMatch() {
    String query = GoogleAdsQueryBuilder.buildCampaignQuery("2024", "endsWith", "all");
    assertThat(query).isEqualTo("SELECT campaign.id, campaign.name, campaign.status FROM campaign WHERE campaign.status IN ('ENABLED', 'PAUSED') AND campaign.name LIKE '%2024'");
  }

  @Test
  void buildCampaignQueryWithDoesNotContainMatch() {
    String query = GoogleAdsQueryBuilder.buildCampaignQuery("Test", "doesNotContain", "active");
    assertThat(query).isEqualTo("SELECT campaign.id, campaign.name, campaign.status FROM campaign WHERE campaign.status = 'ENABLED' AND campaign.name NOT LIKE '%Test%'");
  }

  @Test
  void buildVideoAssetQueryFormatsWithCampaignId() {
    String query = GoogleAdsQueryBuilder.buildVideoAssetQuery("987654");
    assertThat(query).isEqualTo("SELECT ad_group_ad.ad.id, ad_group_ad.ad.name, ad_group_ad.ad.responsive_video_ad.videos FROM ad_group_ad WHERE campaign.id = 987654 AND ad_group_ad.status = 'ENABLED'");
  }
}

