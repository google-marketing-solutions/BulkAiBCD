package com.bulkaibcd.service.ads;

import java.util.ArrayList;
import java.util.List;

public class GoogleAdsQueryBuilder {

  private GoogleAdsQueryBuilder() {
    // Prevent instantiation of utility class
  }

  public static String buildCampaignQuery(String nameMatch, String matchType, String status) {
    StringBuilder query = new StringBuilder("SELECT campaign.id, campaign.name, campaign.status FROM campaign");
    List<String> conditions = new ArrayList<>();

    if ("active".equalsIgnoreCase(status)) {
      conditions.add("campaign.status = 'ENABLED'");
    } else {
      conditions.add("campaign.status IN ('ENABLED', 'PAUSED')");
    }

    if (nameMatch != null && !nameMatch.isBlank()) {
      String escaped = nameMatch.replace("'", "\\'");
      switch (matchType != null ? matchType : "contains") {
        case "startsWith":
          conditions.add("campaign.name LIKE '" + escaped + "%'");
          break;
        case "endsWith":
          conditions.add("campaign.name LIKE '%" + escaped + "'");
          break;
        case "doesNotContain":
          conditions.add("campaign.name NOT LIKE '%" + escaped + "%'");
          break;
        case "contains":
        default:
          conditions.add("campaign.name LIKE '%" + escaped + "%'");
          break;
      }
    }

    if (!conditions.isEmpty()) {
      query.append(" WHERE ").append(String.join(" AND ", conditions));
    }
    return query.toString();
  }

  public static String buildVideoAssetQuery(String campaignId) {
    return "SELECT ad_group_ad.ad.id, ad_group_ad.ad.name, ad_group_ad.ad.responsive_video_ad.videos " +
        "FROM ad_group_ad " +
        "WHERE campaign.id = " + campaignId + " AND ad_group_ad.status = 'ENABLED'";
  }
}
