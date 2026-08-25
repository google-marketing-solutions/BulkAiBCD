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

import {HttpErrorResponse, provideHttpClient} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';

import {environment} from '../../environments/environment';
import {
  AnalysisRequest,
  AnalysisService,
  AppConfig,
  DriveResolveResponse,
  GeneratedDecksResponse,
  SignedUploadUrl,
  VideoMetadata,
} from './analysis.service';

describe('AnalysisService', () => {
  let service: AnalysisService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(AnalysisService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('submitAnalysis POSTs to /input/submit and returns the analysisId as text', () => {
    const body: AnalysisRequest = {
      requesterId: 'u',
      analysisName: 'q3',
      analysisType: 'standard',
    };
    let received: string | undefined;
    service.submitAnalysis(body).subscribe((id) => (received = id));

    const req = httpMock.expectOne(`${environment.apiUrl}/input/submit`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    expect(req.request.responseType).toBe('text');
    req.flush('analysis-abc-123');
    expect(received).toBe('analysis-abc-123');
  });

  it('listAnalyses GETs /input/list/{requesterId}', () => {
    const rows: AnalysisRequest[] = [
      {
        analysisId: '1',
        requesterId: 'u',
        analysisName: 'n',
        analysisType: 't',
        analysisStatus: 'COMPLETED',
      },
    ];
    let received: AnalysisRequest[] = [];
    service.listAnalyses('u').subscribe((r) => (received = r));

    const req = httpMock.expectOne(`${environment.apiUrl}/input/list/u`);
    expect(req.request.method).toBe('GET');
    req.flush(rows);
    expect(received).toEqual(rows);
  });

  it('getAnalysisDetails GETs /input/{analysisId}', () => {
    service.getAnalysisDetails('abc').subscribe();
    const req = httpMock.expectOne(`${environment.apiUrl}/input/abc`);
    expect(req.request.method).toBe('GET');
    req.flush({analysisId: 'abc', requesterId: 'u', analysisName: 'n', analysisType: 't'});
  });

  it('getVideoMetadata GETs /output/videos/{analysisId}', () => {
    const videos: VideoMetadata[] = [
      {id: '1', analysisId: 'a', videoId: 'v1', status: 'COMPLETED', brand: 'Acme'},
    ];
    let received: VideoMetadata[] = [];
    service.getVideoMetadata('a').subscribe((v) => (received = v));

    const req = httpMock.expectOne(`${environment.apiUrl}/output/videos/a`);
    expect(req.request.method).toBe('GET');
    req.flush(videos);
    expect(received).toEqual(videos);
  });

  it('generateSpreadsheet POSTs to /output/report/{id} with X-Drive-Access-Token', () => {
    let received: {sheetUrl: string} | undefined;
    service.generateSpreadsheet('a', 'user-token-xyz').subscribe((r) => (received = r));

    const req = httpMock.expectOne(`${environment.apiUrl}/output/report/a`);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('X-Drive-Access-Token')).toBe('user-token-xyz');
    req.flush({sheetUrl: 'https://docs.google.com/spreadsheets/d/xyz'});
    expect(received?.sheetUrl).toContain('/spreadsheets/d/');
  });

  it('generatePitchDeck POSTs videoIds + token with X-Drive-Access-Token and returns per-video URLs', () => {
    let received: GeneratedDecksResponse | undefined;
    service.generatePitchDeck('a', ['v1', 'v2'], 'user-token-xyz').subscribe((r) => (received = r));

    const req = httpMock.expectOne(`${environment.apiUrl}/output/generate-deck/a`);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('X-Drive-Access-Token')).toBe('user-token-xyz');
    expect(req.request.body).toEqual({videoIds: ['v1', 'v2']});
    req.flush({
      decks: [
        {videoId: 'v1', videoTitle: 'One', deckUrl: 'https://docs.google.com/presentation/d/one'},
        {videoId: 'v2', videoTitle: 'Two', deckUrl: 'https://docs.google.com/presentation/d/two'},
      ],
    });
    expect(received?.decks.length).toBe(2);
  });

  it('requestUploadUrl POSTs to /input/upload-url', () => {
    let received: SignedUploadUrl | undefined;
    service.requestUploadUrl('video.mp4', 'video/mp4').subscribe((r) => (received = r));

    const req = httpMock.expectOne(`${environment.apiUrl}/input/upload-url`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({filename: 'video.mp4', contentType: 'video/mp4'});
    req.flush({url: 'https://upload.url', gcsObjectId: 'bucket/video.mp4'});
    expect(received?.url).toBe('https://upload.url');
    expect(received?.gcsObjectId).toBe('bucket/video.mp4');
  });

  it('getConfig GETs /config', () => {
    let received: AppConfig | undefined;
    service.getConfig().subscribe((cfg) => (received = cfg));

    const req = httpMock.expectOne(`${environment.apiUrl}/config`);
    expect(req.request.method).toBe('GET');
    req.flush({driveIngestServiceAccount: 'sa@test.com', projectId: 'test-project'});
    expect(received?.driveIngestServiceAccount).toBe('sa@test.com');
    expect(received?.projectId).toBe('test-project');
  });

  it('resolveDrive POSTs to /input/drive-resolve', () => {
    let received: DriveResolveResponse | undefined;
    service.resolveDrive('https://drive.google.com/folder').subscribe((r) => (received = r));

    const req = httpMock.expectOne(`${environment.apiUrl}/input/drive-resolve`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({url: 'https://drive.google.com/folder'});
    req.flush({
      videos: [{videoName: 'v1', videoUrl: 'https://drive.google.com/v1', thumbnailUrl: 'thumb'}],
    });
    expect(received?.videos.length).toBe(1);
  });

  it('cancelAnalysis POSTs to /input/{analysisId}/cancel', () => {
    let received: string | undefined;
    service.cancelAnalysis('ana-1').subscribe((r) => (received = r));

    const req = httpMock.expectOne(`${environment.apiUrl}/input/ana-1/cancel`);
    expect(req.request.method).toBe('POST');
    expect(req.request.responseType).toBe('text');
    req.flush('CANCELLED');
    expect(received).toBe('CANCELLED');
  });

  it('deleteAnalysis DELETEs /input/{analysisId}', () => {
    let received: string | undefined;
    service.deleteAnalysis('ana-1').subscribe((r) => (received = r));

    const req = httpMock.expectOne(`${environment.apiUrl}/input/ana-1`);
    expect(req.request.method).toBe('DELETE');
    expect(req.request.responseType).toBe('text');
    req.flush('DELETED');
    expect(received).toBe('DELETED');
  });

  it('propagates HTTP errors as Observable errors', () => {
    let error: HttpErrorResponse | undefined;
    service.listAnalyses('u').subscribe({
      error: (err) => (error = err),
    });
    const req = httpMock.expectOne(`${environment.apiUrl}/input/list/u`);
    req.flush('boom', {status: 500, statusText: 'Server Error'});
    expect(error?.status).toBe(500);
  });
});
