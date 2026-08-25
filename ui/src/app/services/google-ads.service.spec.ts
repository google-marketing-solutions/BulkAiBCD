import {provideHttpClient} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';

import {environment} from '../../environments/environment';
import {
  AdsStatusResponse,
  CampaignDto,
  GoogleAdsService,
  VideoAssetDto,
} from './google-ads.service';

describe('GoogleAdsService', () => {
  let service: GoogleAdsService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(GoogleAdsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('getStatus GETs /ads/status', () => {
    let result: AdsStatusResponse | undefined;
    service.getStatus().subscribe((s) => (result = s));

    const req = httpMock.expectOne(`${environment.apiUrl}/ads/status`);
    expect(req.request.method).toBe('GET');
    req.flush({configured: true, authorized: true});
    expect(result?.configured).toBeTrue();
    expect(result?.authorized).toBeTrue();
  });

  it('configure POSTs credentials to /ads/configure', () => {
    let result: {authorizationUrl: string} | undefined;
    service
      .configure('client-1', 'secret-1', 'dev-1', 'http://localhost:4200')
      .subscribe((r) => (result = r));

    const req = httpMock.expectOne(`${environment.apiUrl}/ads/configure`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      clientId: 'client-1',
      clientSecret: 'secret-1',
      developerToken: 'dev-1',
      frontendOrigin: 'http://localhost:4200',
    });
    req.flush({authorizationUrl: 'https://accounts.google.com/o/oauth2/auth'});
    expect(result?.authorizationUrl).toBe('https://accounts.google.com/o/oauth2/auth');
  });

  it('listCustomers GETs /ads/customers', () => {
    let result: string[] | undefined;
    service.listCustomers().subscribe((c) => (result = c));

    const req = httpMock.expectOne(`${environment.apiUrl}/ads/customers`);
    expect(req.request.method).toBe('GET');
    req.flush(['1234567890', '0987654321']);
    expect(result).toEqual(['1234567890', '0987654321']);
  });

  it('listCampaigns GETs /ads/campaigns with query parameters', () => {
    let result: CampaignDto[] | undefined;
    service
      .listCampaigns('123456', 'Summer', 'contains', 'active')
      .subscribe((c) => (result = c));

    const req = httpMock.expectOne(
      (r) =>
        r.url === `${environment.apiUrl}/ads/campaigns` &&
        r.params.get('customerId') === '123456' &&
        r.params.get('nameMatch') === 'Summer' &&
        r.params.get('matchType') === 'contains' &&
        r.params.get('status') === 'active',
    );
    expect(req.request.method).toBe('GET');
    req.flush([{id: 'c1', name: 'Summer Campaign', status: 'ENABLED'}]);
    expect(result?.length).toBe(1);
    expect(result?.[0].name).toBe('Summer Campaign');
  });

  it('listVideoAssets GETs /ads/video-assets with customerId and campaignId', () => {
    let result: VideoAssetDto[] | undefined;
    service.listVideoAssets('123456', 'camp-1').subscribe((v) => (result = v));

    const req = httpMock.expectOne(
      (r) =>
        r.url === `${environment.apiUrl}/ads/video-assets` &&
        r.params.get('customerId') === '123456' &&
        r.params.get('campaignId') === 'camp-1',
    );
    expect(req.request.method).toBe('GET');
    req.flush([{id: 'v1', name: 'Video 1', youtubeVideoId: 'yt-123'}]);
    expect(result?.length).toBe(1);
    expect(result?.[0].youtubeVideoId).toBe('yt-123');
  });
});

