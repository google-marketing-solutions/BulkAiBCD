import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface VideoInputPayload {
  sourceType: 'youtube' | 'drive' | 'file' | 'id';
  videoName: string;
  videoUrl?: string;
  gcsObjectId?: string;
  thumbnailUrl?: string;
}

export interface SignedUploadUrl {
  url: string;
  gcsObjectId: string;
}

export interface AnalysisRequest {
  analysisId?: string;
  requesterId: string;
  analysisName: string;
  analysisType: string;
  analysisStatus?: string;
  createdAt?: string;
  brandName?: string;
  marketingObjective?: string;
  videos?: VideoInputPayload[];
  customFeatures?: string[];
}

export interface VideoMetadata {
  id: string;
  analysisId: string;
  videoId: string;
  videoName?: string;
  videoUrl?: string;
  thumbnailUrl?: string;
  sourceType?: string;
  status: string;
  errorMessage?: string;
  aScore?: number | null;
  bScore?: number | null;
  cScore?: number | null;
  dScore?: number | null;
  assetName?: string;
  /** @deprecated — previous metadata-extraction fields, kept for backward compat. */
  brand?: string;
  product?: string;
  videoLanguage?: string;
  vertical?: string;
}

@Injectable({ providedIn: 'root' })
export class AnalysisService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = environment.apiUrl;

  submitAnalysis(request: AnalysisRequest): Observable<string> {
    return this.http.post(`${this.apiUrl}/input/submit`, request, {
      responseType: 'text',
    });
  }

  listAnalyses(requesterId: string): Observable<AnalysisRequest[]> {
    return this.http.get<AnalysisRequest[]>(
      `${this.apiUrl}/input/list/${requesterId}`,
    );
  }

  getAnalysisDetails(analysisId: string): Observable<AnalysisRequest> {
    return this.http.get<AnalysisRequest>(`${this.apiUrl}/input/${analysisId}`);
  }

  getVideoMetadata(analysisId: string): Observable<VideoMetadata[]> {
    return this.http.get<VideoMetadata[]>(
      `${this.apiUrl}/output/videos/${analysisId}`,
    );
  }

  /**
   * Creates a Google Sheet in the signed-in user's Drive from the analysis's video
   * metadata. Requires a drive.file-scoped access token from GoogleAuthService.
   */
  generateSpreadsheet(
    analysisId: string,
    driveAccessToken: string,
  ): Observable<{sheetUrl: string}> {
    return this.http.post<{sheetUrl: string}>(
      `${this.apiUrl}/output/report/${analysisId}`,
      {},
      { headers: new HttpHeaders({ 'X-Drive-Access-Token': driveAccessToken }) },
    );
  }

  requestUploadUrl(filename: string, contentType: string): Observable<SignedUploadUrl> {
    return this.http.post<SignedUploadUrl>(`${this.apiUrl}/input/upload-url`, {
      filename,
      contentType,
    });
  }

  getConfig(): Observable<AppConfig> {
    return this.http.get<AppConfig>(`${this.apiUrl}/config`);
  }

  resolveDrive(url: string): Observable<DriveResolveResponse> {
    return this.http.post<DriveResolveResponse>(`${this.apiUrl}/input/drive-resolve`, {url});
  }

  cancelAnalysis(analysisId: string): Observable<string> {
    return this.http.post(`${this.apiUrl}/input/${analysisId}/cancel`, null, {
      responseType: 'text',
    });
  }

  deleteAnalysis(analysisId: string): Observable<string> {
    return this.http.delete(`${this.apiUrl}/input/${analysisId}`, {
      responseType: 'text',
    });
  }

  /**
   * Creates one Google Slides pitch deck per selected video in the signed-in user's
   * Drive. Requires a drive.file-scoped access token from GoogleAuthService.
   */
  generatePitchDeck(
    analysisId: string,
    videoIds: string[],
    driveAccessToken: string,
  ): Observable<GeneratedDecksResponse> {
    return this.http.post<GeneratedDecksResponse>(
      `${this.apiUrl}/output/generate-deck/${analysisId}`,
      {videoIds},
      { headers: new HttpHeaders({ 'X-Drive-Access-Token': driveAccessToken }) },
    );
  }
}

export interface GeneratedDecksResponse {
  decks: Array<{videoId: string; videoTitle: string; deckUrl: string}>;
}

export interface DriveResolveResponse {
  videos: Array<{
    videoName: string;
    videoUrl: string;
    thumbnailUrl: string;
  }>;
}

export interface AppConfig {
  driveIngestServiceAccount: string;
  projectId: string;
}


