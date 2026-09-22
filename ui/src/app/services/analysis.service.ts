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

import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

/**
 * Payload describing a single video input submitted for ABCD analysis.
 */
export interface VideoInputPayload {
  sourceType: 'youtube' | 'drive' | 'file' | 'id';
  videoName: string;
  videoUrl?: string;
  gcsObjectId?: string;
  thumbnailUrl?: string;
  format?: string;
  unlisted?: boolean;
}

/**
 * Augmented File representation carrying analysis metadata and unlisted flags.
 */
export interface AnalysisVideoFile extends File {
  sourceUrl?: string;
  gcsObjectId?: string;
  thumbnailDataUrl?: string | null;
  format?: 'LONG' | 'SHORT' | string;
  unlisted?: boolean;
}

/**
 * Direct Cloud Storage signed upload URL descriptor.
 */
export interface SignedUploadUrl {
  url: string;
  gcsObjectId: string;
}

/**
 * Request payload for creating and submitting a new video analysis batch.
 */
export interface AnalysisRequest {
  analysisId?: string;
  /** Server-derived from the IAP assertion; never sent by the client. */
  requesterId?: string;
  analysisName: string;
  analysisType: string;
  analysisStatus?: string;
  createdAt?: string;
  brandName?: string;
  marketingObjective?: string;
  videos?: VideoInputPayload[];
  customFeaturesLong?: string[];
  customFeaturesShort?: string[];
}

/**
 * Video metadata record containing scoring, dimensions, and detection details.
 */
export interface VideoMetadata {
  id: string;
  analysisId: string;
  videoId: string;
  videoName?: string;
  videoUrl?: string;
  thumbnailUrl?: string;
  sourceType?: string;
  format?: string;
  status: string;
  errorMessage?: string;
  signedUrl?: string;
  aScore?: number | null;
  bScore?: number | null;
  cScore?: number | null;
  dScore?: number | null;
  assetName?: string;
  relevantFeatures?: string[];
  notDetected?: string[];
  /** @deprecated — previous metadata-extraction fields, kept for backward compat. */
  brand?: string;
  product?: string;
  videoLanguage?: string;
  vertical?: string;
}

/**
 * Client service interacting with the BulkAI ABCD backend APIs.
 */
@Injectable({ providedIn: 'root' })
export class AnalysisService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = environment.apiUrl;

  /**
   * Submits a new analysis request to the backend.
   *
   * @param request the analysis configuration and video inputs
   * @returns an Observable emitting the assigned analysisId string
   */
  submitAnalysis(request: AnalysisRequest): Observable<string> {
    return this.http.post(`${this.apiUrl}/input/submit`, request, {
      responseType: 'text',
    });
  }

  /**
   * Lists analyses belonging to the signed-in user.
   *
   * The backend derives the requester from the IAP assertion, so no identifier is
   * passed here. It previously came from the URL, which let anyone read another
   * user's jobs by editing it.
   *
   * @returns an Observable emitting the caller's analysis records
   */
  listAnalyses(): Observable<AnalysisRequest[]> {
    return this.http.get<AnalysisRequest[]>(`${this.apiUrl}/input/list`);
  }

  /**
   * Fetches details of a specific analysis run.
   *
   * @param analysisId the analysis identifier
   * @returns an Observable emitting the analysis details
   */
  getAnalysisDetails(analysisId: string): Observable<AnalysisRequest> {
    return this.http.get<AnalysisRequest>(`${this.apiUrl}/input/${analysisId}`);
  }

  /**
   * Fetches all video metadata and evaluation scores for an analysis.
   *
   * @param analysisId the analysis identifier
   * @returns an Observable emitting the list of video metadata records
   */
  getVideoMetadata(analysisId: string): Observable<VideoMetadata[]> {
    return this.http.get<VideoMetadata[]>(
      `${this.apiUrl}/output/videos/${analysisId}`,
    );
  }

  /**
   * Creates a Google Sheet in the signed-in user's Drive from the analysis's video metadata.
   *
   * @param analysisId the analysis identifier
   * @param driveAccessToken OAuth2 access token with drive.file scope
   * @returns an Observable emitting the generated spreadsheet URL
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

  /**
   * Requests a direct GCS signed upload URL for a local video file.
   *
   * @param filename name of the file to upload
   * @param contentType MIME type of the file
   * @returns an Observable emitting the signed upload URL and GCS object ID
   */
  requestUploadUrl(filename: string, contentType: string): Observable<SignedUploadUrl> {
    return this.http.post<SignedUploadUrl>(`${this.apiUrl}/input/upload-url`, {
      filename,
      contentType,
    });
  }

  /**
   * Fetches server configuration and ingest service account details.
   *
   * @returns an Observable emitting the app configuration
   */
  getConfig(): Observable<AppConfig> {
    return this.http.get<AppConfig>(`${this.apiUrl}/config`);
  }

  /**
   * Resolves video links inside a Google Drive folder or file URL.
   *
   * @param url the Google Drive URL
   * @returns an Observable emitting resolved video entries
   */
  resolveDrive(url: string): Observable<DriveResolveResponse> {
    return this.http.post<DriveResolveResponse>(`${this.apiUrl}/input/drive-resolve`, {url});
  }

  /**
   * Resolves YouTube URLs, checking titles and privacy (unlisted) status.
   *
   * @param urls list of YouTube URLs to resolve
   * @returns an Observable emitting resolved YouTube video info objects
   */
  resolveYouTubeUrls(urls: string[]): Observable<YouTubeVideoInfo[]> {
    return this.http.post<YouTubeVideoInfo[]>(`${this.apiUrl}/input/youtube-resolve`, {urls});
  }

  /**
   * Cancels an ongoing analysis execution.
   *
   * @param analysisId the analysis identifier
   * @returns an Observable emitting the cancellation result message
   */
  cancelAnalysis(analysisId: string): Observable<string> {
    return this.http.post(`${this.apiUrl}/input/${analysisId}/cancel`, null, {
      responseType: 'text',
    });
  }

  /**
   * Deletes an analysis record and its associated metadata.
   *
   * @param analysisId the analysis identifier
   * @returns an Observable emitting the deletion result message
   */
  deleteAnalysis(analysisId: string): Observable<string> {
    return this.http.delete(`${this.apiUrl}/input/${analysisId}`, {
      responseType: 'text',
    });
  }

  /**
   * Creates Google Slides pitch decks in the signed-in user's Drive.
   *
   * @param analysisId the analysis identifier
   * @param videoIds the list of video IDs to generate decks for
   * @param driveAccessToken OAuth2 access token with drive.file scope
   * @returns an Observable emitting generated deck URLs
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

/**
 * Response payload containing generated Google Slides presentation URLs.
 */
export interface GeneratedDecksResponse {
  decks: Array<{videoId: string; videoTitle: string; deckUrl: string}>;
}

/**
 * Response payload containing videos resolved from Google Drive.
 */
export interface DriveResolveResponse {
  videos: Array<{
    videoName: string;
    videoUrl: string;
    thumbnailUrl: string;
  }>;
}

/**
 * Runtime server configuration and Google Cloud environment settings.
 */
export interface AppConfig {
  driveIngestServiceAccount: string;
  projectId: string;
}

/**
 * Metadata and privacy status resolved for a YouTube video.
 */
export interface YouTubeVideoInfo {
  video_id?: string;
  videoId?: string;
  url: string;
  title: string;
  unlisted: boolean;
  error_message?: string;
}
