import {CommonModule} from '@angular/common';
import {ChangeDetectionStrategy, Component, inject, signal} from '@angular/core';
import {FormControl, ReactiveFormsModule, Validators} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatIconModule} from '@angular/material/icon';
import {MatInputModule} from '@angular/material/input';
import {MatSelectModule} from '@angular/material/select';
import {MatRadioModule} from '@angular/material/radio';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';
import {Router} from '@angular/router';

import {AnalysisService, VideoInputPayload} from '../../services/analysis.service';
import {VideoInputTabsComponent} from './video-input-tabs/video-input-tabs.component';
import {AnalysisConfigComponent} from './analysis-config/analysis-config.component';
import {InputQueueComponent} from './input-queue/input-queue.component';

const DEFAULT_REQUESTER_ID = 'default-user';
const MAX_VIDEOS_PER_ANALYSIS = 25;

@Component({
  selector: 'app-new-analysis',
  templateUrl: './new-analysis.component.html',
  styleUrls: ['./new-analysis.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatRadioModule,
    VideoInputTabsComponent,
    AnalysisConfigComponent,
    InputQueueComponent,
    MatButtonModule,
    MatIconModule,
    MatSnackBarModule,
  ],
})
export class NewAnalysisComponent {
  private readonly analysisService = inject(AnalysisService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly brandName = new FormControl('', [Validators.required]);
  protected readonly marketingObjective = new FormControl('core_unknown');
  protected readonly format = new FormControl('LONG', [Validators.required]);
  protected readonly analysisType = new FormControl<string | null>('standard');
  protected readonly customFeatures = new FormControl<{long: string[], short: string[]} | null>({long: [], short: []});
  protected readonly videos = signal<File[]>([]);
  protected readonly submitting = signal(false);

  protected addFiles(files: File[]) {
    const remaining = MAX_VIDEOS_PER_ANALYSIS - this.videos().length;
    if (remaining <= 0) {
      this.snackBar.open(
        `Queue is full (max ${MAX_VIDEOS_PER_ANALYSIS} videos per analysis).`,
        'Dismiss',
        {duration: 5000},
      );
      return;
    }
    const accepted = files.slice(0, remaining);
    
    // Attach current format to the file object
    const currentFormat = this.format.value ?? 'LONG';
    accepted.forEach(f => {
      (f as any).format = currentFormat;
    });

    if (files.length - accepted.length > 0) {
      this.snackBar.open(
        `Only ${accepted.length} of ${files.length} added \u2014 max ${MAX_VIDEOS_PER_ANALYSIS} per analysis.`,
        'Dismiss',
        {duration: 5000},
      );
    }
    this.videos.update((videos) => [...videos, ...accepted]);
  }

  protected removeVideo(index: number) {
    this.videos.update((videos) => videos.filter((_, i) => i !== index));
  }

  protected clearVideos() {
    this.videos.set([]);
  }

  protected runAnalysis() {
    if (this.brandName.invalid || this.format.invalid || this.videos().length === 0) return;
    this.submitting.set(true);
    const videos: VideoInputPayload[] = this.videos().map((f) => {
      const meta = f as File & {
        sourceUrl?: string;
        gcsObjectId?: string;
        thumbnailDataUrl?: string | null;
        format?: string;
      };
      return {
        sourceType: detectSourceType(f),
        videoName: f.name,
        videoUrl: meta.sourceUrl,
        gcsObjectId: meta.gcsObjectId,
        thumbnailUrl: meta.thumbnailDataUrl ?? undefined,
        format: meta.format ?? 'LONG',
      };
    });
    this.analysisService
      .submitAnalysis({
        requesterId: DEFAULT_REQUESTER_ID,
        analysisName: this.brandName.value ?? '',
        analysisType: this.analysisType.value ?? 'standard',
        customFeaturesLong: this.customFeatures.value?.long ?? [],
        customFeaturesShort: this.customFeatures.value?.short ?? [],
        brandName: this.brandName.value ?? '',
        marketingObjective: this.marketingObjective.value ?? 'core_unknown',
        videos,
      })
      .subscribe({
        next: (analysisId) => {
          this.submitting.set(false);
          this.snackBar.open(`Analysis ${analysisId} submitted`, 'Dismiss', {
            duration: 5000,
          });
          this.router.navigate(['/list']);
        },
        error: (err) => {
          this.submitting.set(false);
          this.snackBar.open(
            `Failed to submit: ${err?.message ?? 'unknown error'}`,
            'Dismiss',
            {duration: 8000},
          );
        },
      });
  }
}

function detectSourceType(f: File): 'youtube' | 'drive' | 'id' | 'file' {
  switch (f.type) {
    case 'youtube/url': return 'youtube';
    case 'drive/url': return 'drive';
    case 'ads/id': return 'id';
    case 'video/uploaded': return 'file';
    default: return 'file';
  }
}
