import {HttpErrorResponse, provideHttpClient} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';

import {environment} from '../../environments/environment';
import {AnalysisRequest, AnalysisService, VideoMetadata} from './analysis.service';

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

  it('generateSpreadsheet POSTs to /output/report/{id} with X-Google-Access-Token', () => {
    let received: {sheetUrl: string} | undefined;
    service.generateSpreadsheet('a', 'user-token-xyz').subscribe((r) => (received = r));

    const req = httpMock.expectOne(`${environment.apiUrl}/output/report/a`);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('X-Google-Access-Token')).toBe('user-token-xyz');
    req.flush({sheetUrl: 'https://docs.google.com/spreadsheets/d/xyz'});
    expect(received?.sheetUrl).toContain('/spreadsheets/d/');
  });

  it('generatePitchDeck POSTs videoIds + token and returns per-video URLs', () => {
    let received: {decks: Array<{videoId: string; videoTitle: string; deckUrl: string}>} | undefined;
    service.generatePitchDeck('a', ['v1', 'v2'], 'user-token-xyz').subscribe((r) => (received = r));

    const req = httpMock.expectOne(`${environment.apiUrl}/output/generate-deck/a`);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('X-Google-Access-Token')).toBe('user-token-xyz');
    expect(req.request.body).toEqual({videoIds: ['v1', 'v2']});
    req.flush({
      decks: [
        {videoId: 'v1', videoTitle: 'One', deckUrl: 'https://docs.google.com/presentation/d/one'},
        {videoId: 'v2', videoTitle: 'Two', deckUrl: 'https://docs.google.com/presentation/d/two'},
      ],
    });
    expect(received?.decks.length).toBe(2);
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
