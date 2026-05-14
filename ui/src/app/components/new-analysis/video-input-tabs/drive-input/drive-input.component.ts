import {Clipboard, ClipboardModule} from '@angular/cdk/clipboard';
import {CommonModule} from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  output,
  signal,
} from '@angular/core';
import {FormControl, ReactiveFormsModule, Validators} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatIconModule} from '@angular/material/icon';
import {MatInputModule} from '@angular/material/input';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';
import {MatTooltipModule} from '@angular/material/tooltip';

import {AnalysisService} from '../../../../services/analysis.service';

const DRIVE_URL_PATTERN = /^https?:\/\/(drive|docs)\.google\.com\/.+/i;

@Component({
  selector: 'app-drive-input',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <mat-form-field appearance="outline" class="full-width">
      <mat-label>Paste a Google Drive file or folder URL</mat-label>
      <input matInput required [formControl]="urlControl">
    </mat-form-field>
    <button
      mat-flat-button
      color="primary"
      (click)="addUrl()"
      [disabled]="urlControl.invalid || resolving()"
    >
      <mat-spinner *ngIf="resolving()" diameter="16"></mat-spinner>
      {{ resolving() ? 'Resolving\u2026' : 'Fetch Videos' }}
    </button>
    <div class="constraint-banner">
      <mat-icon>info</mat-icon>
      <span>
        Share the Drive file or folder with
        <ng-container *ngIf="serviceAccount() as sa; else loadingSa">
          <span class="sa-pill">
            <code>{{ sa }}</code>
            <button
              mat-icon-button
              type="button"
              class="copy-btn"
              [cdkCopyToClipboard]="sa"
              (click)="onCopySa()"
              [matTooltip]="copied() ? 'Copied!' : 'Copy'"
              aria-label="Copy service account email"
            >
              <mat-icon>{{ copied() ? 'check' : 'content_copy' }}</mat-icon>
            </button>
          </span>
        </ng-container>
        <ng-template #loadingSa><em>(loading service account\u2026)</em></ng-template>
        (Viewer access). Paste a folder URL to add every video in it at once.
      </span>
    </div>
  `,
  styleUrl: './drive-input.component.scss',
  imports: [
    ClipboardModule,
    CommonModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatTooltipModule,
    ReactiveFormsModule,
  ],
})
export class DriveInputComponent implements OnInit {
  readonly filesAdded = output<File[]>();
  private readonly analysisService = inject(AnalysisService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly clipboard = inject(Clipboard);
  readonly serviceAccount = signal<string | null>(null);
  readonly resolving = signal(false);
  readonly copied = signal(false);
  urlControl = new FormControl('', [
    Validators.required,
    Validators.pattern(DRIVE_URL_PATTERN),
  ]);

  onCopySa() {
    // cdkCopyToClipboard already wrote the value; we just flash a "Copied!"
    // tooltip + icon swap so users know it landed.
    this.copied.set(true);
    setTimeout(() => this.copied.set(false), 1500);
  }

  ngOnInit() {
    this.analysisService.getConfig().subscribe({
      next: (cfg) => this.serviceAccount.set(cfg.driveIngestServiceAccount || null),
      error: () => this.serviceAccount.set(null),
    });
  }

  addUrl() {
    const url = (this.urlControl.value ?? '').trim();
    if (!url) return;
    this.resolving.set(true);
    this.analysisService.resolveDrive(url).subscribe({
      next: (res) => {
        this.resolving.set(false);
        const videos = res?.videos ?? [];
        if (videos.length === 0) {
          this.snackBar.open(
            "That Drive link didn't yield any videos. Is it a folder without video files?",
            'Dismiss',
            {duration: 6000},
          );
          return;
        }
        const files = videos.map((v) => {
          // Carry a real File wrapper so the existing queue / submit pipeline
          // treats it like any other URL-sourced row. sourceUrl feeds
          // videoUrl on the payload; thumbnailDataUrl feeds the queue + report.
          const file = new File([v.videoUrl], v.videoName || v.videoUrl, {
            type: 'drive/url',
          });
          return Object.assign(file, {
            sourceUrl: v.videoUrl,
            thumbnailDataUrl: v.thumbnailUrl || null,
          }) as File & {sourceUrl: string; thumbnailDataUrl: string | null};
        });
        this.filesAdded.emit(files);
        this.urlControl.reset('', {emitEvent: false});
        this.urlControl.markAsPristine();
        this.urlControl.markAsUntouched();
      },
      error: (err) => {
        this.resolving.set(false);
        this.snackBar.open(friendlyDriveError(err, this.serviceAccount()), 'Dismiss', {
          duration: 10000,
        });
      },
    });
  }
}

function friendlyDriveError(err: unknown, serviceAccount: string | null): string {
  const e = err as {status?: number; error?: {code?: string}} | undefined;
  const status = e?.status;
  const code = e?.error?.code;
  const sa = serviceAccount ?? 'the service account';
  if (status === 400 || code === 'UNRECOGNIZED_URL') {
    return 'Not a valid Drive URL.';
  }
  if (status === 403 || status === 404 || code === 'ACCESS_DENIED') {
    return `No access — share with ${sa} (Viewer).`;
  }
  return `Drive unreachable. Share with ${sa} and retry.`;
}
