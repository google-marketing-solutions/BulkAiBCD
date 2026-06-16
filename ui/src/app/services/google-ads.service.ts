import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface AdsStatusResponse {
  configured: boolean;
  authorized: boolean;
}

export interface CampaignDto {
  id: string;
  name: string;
  status: string;
}

export interface VideoAssetDto {
  id: string;
  name: string;
  youtubeVideoId: string;
}

@Injectable({ providedIn: 'root' })
export class GoogleAdsService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = environment.apiUrl;

  getStatus(): Observable<AdsStatusResponse> {
    return this.http.get<AdsStatusResponse>(`${this.apiUrl}/ads/status`);
  }

  configure(
    clientId: string,
    clientSecret: string,
    developerToken: string,
    frontendOrigin: string
  ): Observable<{ authorizationUrl: string }> {
    return this.http.post<{ authorizationUrl: string }>(
      `${this.apiUrl}/ads/configure`,
      { clientId, clientSecret, developerToken, frontendOrigin }
    );
  }

  listCustomers(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/ads/customers`);
  }

  listCampaigns(
    customerId: string,
    nameMatch?: string,
    matchType?: string,
    status?: string
  ): Observable<CampaignDto[]> {
    let params = new HttpParams().set('customerId', customerId);
    if (nameMatch) {
      params = params.set('nameMatch', nameMatch);
    }
    if (matchType) {
      params = params.set('matchType', matchType);
    }
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<CampaignDto[]>(`${this.apiUrl}/ads/campaigns`, { params });
  }

  listVideoAssets(customerId: string, campaignId: string): Observable<VideoAssetDto[]> {
    const params = new HttpParams()
      .set('customerId', customerId)
      .set('campaignId', campaignId);
    return this.http.get<VideoAssetDto[]>(`${this.apiUrl}/ads/video-assets`, { params });
  }
}
