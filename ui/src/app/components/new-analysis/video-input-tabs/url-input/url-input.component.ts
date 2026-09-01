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

import {ChangeDetectionStrategy, Component, inject, input, output, signal} from '@angular/core';
import {FormControl, ReactiveFormsModule, Validators} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatIconModule} from '@angular/material/icon';
import {MatInputModule} from '@angular/material/input';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';
import {firstValueFrom} from 'rxjs';
import {AnalysisService, AnalysisVideoFile} from '../../../../services/analysis.service';

const YOUTUBE_URL_PATTERN =
  /^https?:\/\/(www\.)?(youtube\.com\/watch\?v=|youtu\.be\/)[A-Za-z0-9_-]+/;
const YOUTUBE_SHORT_PATTERN =
  /^https?:\/\/(www\.)?youtube\.com\/shorts\/[A-Za-z0-9_-]+/;

@Component({
  selector: 'app-url-input',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <mat-form-field appearance="outline" class="full-width url-textarea-field">
      <mat-label>Paste YouTube URLs (One per line)</mat-label>
      <textarea
        matInput
        required
        [formControl]="urlsControl"
        rows="6"
        wrap="soft"
        spellcheck="false"
        placeholder="https://www.youtube.com/watch?v=...&#10;https://youtu.be/..."
        [readonly]="validating()"
      ></textarea>
      <mat-hint align="end">{{ lineCount() }} line{{ lineCount() === 1 ? '' : 's' }}</mat-hint>
    </mat-form-field>
    <button
      mat-flat-button
      color="primary"
      (click)="addUrls()"
      [disabled]="urlsControl.invalid || !urlsControl.value?.trim() || validating()"
      class="validate-btn"
    >
      @if (validating()) {
        <mat-spinner diameter="20" class="btn-spinner"></mat-spinner>
      }
      {{ validating() ? 'Validating URLs...' : 'Validate and Add URLs to queue' }}
    </button>
  `,
  styleUrl: './url-input.component.scss',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    ReactiveFormsModule,
  ],
})
export class UrlInputComponent {
  readonly currentCount = input(0);
  readonly filesAdded = output<File[]>();
  private readonly snackBar = inject(MatSnackBar);
  private readonly analysisService = inject(AnalysisService);

  urlsControl = new FormControl('', [Validators.required]);
  validating = signal(false);

  lineCount(): number {
    const v = (this.urlsControl.value ?? '').trim();
    if (!v) return 0;
    return v.split(/\r?\n/).filter((l) => l.trim().length > 0).length;
  }

  async addUrls() {
    const raw = (this.urlsControl.value ?? '').trim();
    if (!raw) return;
    const lines = raw.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
    const valid: string[] = [];
    const invalid: string[] = [];
    for (const line of lines) {
      if (YOUTUBE_URL_PATTERN.test(line)) valid.push(line);
      else if (YOUTUBE_SHORT_PATTERN.test(line)) valid.push(line);
      else invalid.push(line);
    }
    if (invalid.length > 0) {
      this.snackBar.open(
        `Skipped ${invalid.length} invalid URL${invalid.length > 1 ? 's' : ''}.`,
        'Dismiss',
        {duration: 5000},
      );
    }
    if (valid.length === 0) return;

    this.validating.set(true);
    try {
      try {
        const resolvedList = await firstValueFrom(this.analysisService.resolveYouTubeUrls(valid));
        const files: AnalysisVideoFile[] = resolvedList.map((res) => {
          const label = res.title ? res.title : res.url;
          const file = new File([res.url], label, {type: 'youtube/url'}) as AnalysisVideoFile;
          file.sourceUrl = res.url;
          file.unlisted = res.unlisted;
          return file;
        });

        this.filesAdded.emit(files);
        this.urlsControl.reset('', {emitEvent: false});
        this.urlsControl.markAsPristine();
        this.urlsControl.markAsUntouched();
      } catch {
        // Resolve titles in parallel via oEmbed fallback if backend resolver is unavailable
        const files: AnalysisVideoFile[] = await Promise.all(
          valid.map(async (url) => {
            const title = await fetchYoutubeTitle(url).catch(() => null);
            const label = title ? title : url;
            const file = new File([url], label, {type: 'youtube/url'}) as AnalysisVideoFile;
            file.sourceUrl = url;
            file.unlisted = false;
            return file;
          }),
        );

        this.filesAdded.emit(files);
        this.urlsControl.reset('', {emitEvent: false});
        this.urlsControl.markAsPristine();
        this.urlsControl.markAsUntouched();
      }
    } finally {
      this.validating.set(false);
    }
  }
}

/**
 * Look up a YouTube video's title using the public oEmbed endpoint (no API key
 * needed, CORS-enabled). Returns null on any failure so callers can fall back
 * to the raw URL.
 */
async function fetchYoutubeTitle(url: string): Promise<string | null> {
  const endpoint = `https://www.youtube.com/oembed?url=${encodeURIComponent(url)}&format=json`;
  const res = await fetch(endpoint);
  if (!res.ok) return null;
  const data = await res.json();
  return typeof data?.title === 'string' ? data.title : null;
}
