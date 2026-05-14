import {CommonModule} from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  inject,
  output,
  signal,
} from '@angular/core';
import {MatIconModule} from '@angular/material/icon';
import {MatProgressBarModule} from '@angular/material/progress-bar';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';
import {firstValueFrom} from 'rxjs';

import {AnalysisService} from '../../../../services/analysis.service';

const MAX_FILE_SIZE_BYTES = 360 * 1024 * 1024; // 360MB
const MAX_DURATION_SECONDS = 8 * 60; // 8 minutes

interface UploadProgress {
  name: string;
  state: 'uploading' | 'done' | 'error';
  percent: number;
}

@Component({
  selector: 'app-file-upload',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './file-upload.component.html',
  styleUrl: './file-upload.component.scss',
  imports: [CommonModule, MatIconModule, MatProgressBarModule, MatSnackBarModule],
})
export class FileUploadComponent {
  readonly filesAdded = output<File[]>();
  private readonly snackBar = inject(MatSnackBar);
  private readonly analysisService = inject(AnalysisService);

  isDragOver = false;
  readonly progress = signal<UploadProgress[]>([]);

  onDragOver(event: DragEvent) {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = true;
  }

  onDragLeave(event: DragEvent) {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;
  }

  async onDrop(event: DragEvent) {
    event.preventDefault();
    event.stopPropagation();
    this.isDragOver = false;
    const files = Array.from(event.dataTransfer?.files ?? []);
    if (files.length === 0) return;
    await this.handleFiles(files);
  }

  async onFileSelected(event: Event) {
    const input = event.target as HTMLInputElement;
    if (!input.files) return;
    await this.handleFiles(Array.from(input.files));
    input.value = '';
  }

  private async handleFiles(rawFiles: File[]) {
    const candidates: File[] = [];
    for (const file of rawFiles) {
      if (!file.type.startsWith('video/')) {
        this.snackBar.open(`File "${file.name}" is not a video file, skipping.`, 'Dismiss', {duration: 5000});
        continue;
      }
      if (file.size > MAX_FILE_SIZE_BYTES) {
        this.snackBar.open(`File "${file.name}" exceeds 360MB, skipping.`, 'Dismiss', {duration: 5000});
        continue;
      }
      candidates.push(file);
    }

    const valid: File[] = [];
    for (const file of candidates) {
      try {
        const duration = await readVideoDurationSeconds(file);
        if (duration > MAX_DURATION_SECONDS) {
          this.snackBar.open(
            `File "${file.name}" is ${formatDuration(duration)} \u2014 max is 8:00, skipping.`,
            'Dismiss',
            {duration: 5000},
          );
          continue;
        }
        valid.push(file);
      } catch {
        valid.push(file);
      }
    }

    if (valid.length === 0) return;

    // Stage each file to GCS via signed URL, then emit File-like wrappers carrying gcsObjectId.
    this.progress.set(valid.map((f) => ({name: f.name, state: 'uploading', percent: 0})));
    const uploaded: File[] = [];
    for (let i = 0; i < valid.length; i++) {
      const file = valid[i];
      try {
        const {url, gcsObjectId} = await firstValueFrom(
          this.analysisService.requestUploadUrl(file.name, file.type || 'video/mp4'),
        );
        await uploadWithProgress(url, file, (pct) => {
          this.progress.update((items) =>
            items.map((it, idx) => (idx === i ? {...it, percent: pct} : it)),
          );
        });
        this.progress.update((items) =>
          items.map((it, idx) =>
            idx === i ? {...it, state: 'done', percent: 100} : it,
          ),
        );
        // Extract a single-frame thumbnail from the local file. Runs after the
        // GCS upload so a failure here (e.g., codec the browser can't decode)
        // doesn't block the submit — we just fall back to the generic movie icon.
        let thumbnailDataUrl: string | null = null;
        try {
          thumbnailDataUrl = await captureVideoThumbnail(file);
        } catch {
          thumbnailDataUrl = null;
        }
        const tagged = Object.assign(
          new File([file], file.name, {type: 'video/uploaded'}),
          {gcsObjectId, thumbnailDataUrl},
        ) as File & {gcsObjectId: string; thumbnailDataUrl: string | null};
        uploaded.push(tagged);
      } catch (err: any) {
        this.progress.update((items) =>
          items.map((it, idx) => (idx === i ? {...it, state: 'error', percent: 0} : it)),
        );
        this.snackBar.open(`Upload failed for "${file.name}": ${err?.message ?? err}`, 'Dismiss', {duration: 8000});
      }
    }

    if (uploaded.length > 0) this.filesAdded.emit(uploaded);
    // Clear progress display after a short pause so user sees the green ticks.
    setTimeout(() => this.progress.set([]), 2000);
  }
}

function readVideoDurationSeconds(file: File): Promise<number> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const video = document.createElement('video');
    video.preload = 'metadata';
    const cleanup = () => URL.revokeObjectURL(url);
    video.onloadedmetadata = () => {
      const duration = video.duration;
      cleanup();
      if (Number.isFinite(duration)) resolve(duration);
      else reject(new Error('non-finite duration'));
    };
    video.onerror = () => {
      cleanup();
      reject(new Error('failed to load video metadata'));
    };
    video.src = url;
  });
}

function uploadWithProgress(
  url: string,
  file: File,
  onProgress: (percent: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url, true);
    xhr.setRequestHeader('Content-Type', file.type || 'video/mp4');
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve();
      else reject(new Error(`PUT failed: HTTP ${xhr.status}`));
    };
    xhr.onerror = () => reject(new Error('Network error during upload'));
    xhr.send(file);
  });
}

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = Math.round(seconds % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
}

// Draws one frame from the video near the start onto a small offscreen canvas
// and returns a JPEG data URL (~10-20KB at quality 0.7). Small enough to store
// as a Firestore string field (1MB max).
const THUMB_WIDTH = 320;
const THUMB_HEIGHT = 180;

function captureVideoThumbnail(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const blobUrl = URL.createObjectURL(file);
    const video = document.createElement('video');
    video.preload = 'auto';
    video.muted = true;
    video.playsInline = true;
    video.crossOrigin = 'anonymous';

    let done = false;
    const cleanup = () => {
      URL.revokeObjectURL(blobUrl);
      video.remove();
    };
    const finish = (fn: () => void) => {
      if (done) return;
      done = true;
      try { fn(); } finally { cleanup(); }
    };

    video.onerror = () => finish(() => reject(new Error('video decode failed')));
    video.onloadedmetadata = () => {
      // Seek ~1s in so we skip black frames at t=0. For shorter clips, use 10%.
      const target = Math.min(video.duration * 0.1, 1.0);
      video.currentTime = Number.isFinite(target) && target > 0 ? target : 0;
    };
    video.onseeked = () => {
      finish(() => {
        const canvas = document.createElement('canvas');
        canvas.width = THUMB_WIDTH;
        canvas.height = THUMB_HEIGHT;
        const ctx = canvas.getContext('2d');
        if (!ctx) return reject(new Error('no 2d context'));
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        resolve(canvas.toDataURL('image/jpeg', 0.7));
      });
    };

    video.src = blobUrl;
  });
}
