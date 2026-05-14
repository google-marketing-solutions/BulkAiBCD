import {ChangeDetectionStrategy, Component, inject, input, output} from '@angular/core';
import {FormControl, ReactiveFormsModule, Validators} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatIconModule} from '@angular/material/icon';
import {MatInputModule} from '@angular/material/input';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';

const YOUTUBE_URL_PATTERN =
  /^https?:\/\/(www\.)?(youtube\.com\/watch\?v=|youtu\.be\/)[A-Za-z0-9_-]+/;

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
      ></textarea>
      <mat-hint align="end">{{ lineCount() }} line{{ lineCount() === 1 ? '' : 's' }}</mat-hint>
    </mat-form-field>
    <button
      mat-flat-button
      color="primary"
      (click)="addUrls()"
      [disabled]="urlsControl.invalid || !urlsControl.value?.trim()"
    >
      Validate and Add URLs to queue
    </button>
    <div class="constraint-banner">
      <mat-icon>info</mat-icon>
      <span>Constraint: Max 25 YouTube URLs.</span>
    </div>
  `,
  styleUrl: './url-input.component.scss',
  imports: [
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatSnackBarModule,
    ReactiveFormsModule,
  ],
})
export class UrlInputComponent {
  readonly currentCount = input(0);
  readonly filesAdded = output<File[]>();
  private readonly snackBar = inject(MatSnackBar);

  urlsControl = new FormControl('', [Validators.required]);

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

    // Resolve titles in parallel; fall back to the URL if lookup fails.
    const files = await Promise.all(
      valid.map(async (url) => {
        const title = await fetchYoutubeTitle(url).catch(() => null);
        // File.name is what the queue + breakdown table render; we put the title
        // there and keep the URL as the content so the backend still stores it.
        const label = title ? title : url;
        const file = new File([url], label, {type: 'youtube/url'});
        // Stash the URL alongside so NewAnalysisComponent can send it as videoLink.
        (file as File & {sourceUrl: string}).sourceUrl = url;
        return file;
      }),
    );

    this.filesAdded.emit(files);
    this.urlsControl.reset('', {emitEvent: false});
    this.urlsControl.markAsPristine();
    this.urlsControl.markAsUntouched();
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
