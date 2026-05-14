import {CommonModule} from '@angular/common';
import {ChangeDetectionStrategy, Component, computed, inject, input, signal} from '@angular/core';
import {MatButtonModule} from '@angular/material/button';
import {MatIconModule} from '@angular/material/icon';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';

import {AnalysisService, VideoMetadata} from '../../../services/analysis.service';
import {AuthFailure, GoogleAuthService} from '../../../services/google-auth.service';

@Component({
  selector: 'app-aggregate-summary',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './aggregate-summary.component.html',
  styleUrl: './aggregate-summary.component.scss',
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
  ],
})
export class AggregateSummaryComponent {
  private readonly analysisService = inject(AnalysisService);
  private readonly googleAuth = inject(GoogleAuthService);
  private readonly snackBar = inject(MatSnackBar);

  readonly videos = input<VideoMetadata[]>([]);
  readonly analysisId = input<string>('');
  readonly marketingObjective = input<string>('');

  readonly generating = signal(false);

  readonly avgScore = computed<number>(() => {
    const v = this.videos();
    if (v.length === 0) return 0;
    let sum = 0;
    let count = 0;
    for (const m of v) {
      const present: number[] = [];
      if (m.aScore != null) present.push(m.aScore);
      if (m.bScore != null) present.push(m.bScore);
      if (m.cScore != null) present.push(m.cScore);
      if (m.dScore != null) present.push(m.dScore);
      if (present.length === 0) continue;
      sum += present.reduce((s, n) => s + n, 0) / present.length;
      count++;
    }
    return count === 0 ? 0 : Math.round(sum / count);
  });

  readonly objectiveLabel = computed(() => {
    const v = this.marketingObjective();
    switch (v) {
      case 'core_unknown': return 'Core/Unknown';
      case 'awareness': return 'Awareness';
      case 'consideration': return 'Consideration';
      case 'conversion': return 'Conversion';
      case 'brand_building': return 'Brand Building';
      default: return v || '—';
    }
  });

  scoreClass(s: number): string {
    if (s >= 90) return 'score-green';
    if (s >= 70) return 'score-yellow';
    if (s >= 50) return 'score-orange';
    return 'score-red';
  }

  async openDetailedSpreadsheet(): Promise<void> {
    const id = this.analysisId();
    if (!id || this.generating()) return;

    let token: string;
    try {
      token = await this.googleAuth.requestDriveToken();
    } catch (err) {
      this.showAuthSnackbar(err);
      return;
    }

    this.generating.set(true);
    this.analysisService.generateSpreadsheet(id, token).subscribe({
      next: (res) => {
        this.generating.set(false);
        if (!res?.sheetUrl) {
          this.snackBar.open(
            'Sheet request succeeded but no URL was returned.',
            'Dismiss',
            {duration: 6000},
          );
          return;
        }
        window.open(res.sheetUrl, '_blank', 'noopener');
      },
      error: (err) => {
        this.generating.set(false);
        const msg = err?.error?.error ?? err?.message ?? 'Unknown error';
        this.snackBar.open(`Sheet generation failed: ${msg}`, 'Dismiss', {duration: 10000});
      },
    });
  }

  private showAuthSnackbar(err: unknown): void {
    if (err instanceof AuthFailure) {
      if (err.reason === 'USER_DISMISSED') {
        this.snackBar.open(
          'Spreadsheet needs permission to save to your Drive. Click the button again to approve.',
          'Dismiss',
          {duration: 8000},
        );
        return;
      }
      if (err.reason === 'POPUP_BLOCKED') {
        this.snackBar.open(
          'Your browser blocked the sign-in popup. Allow popups for this site, then try again.',
          'Dismiss',
          {duration: 10000},
        );
        return;
      }
      if (err.reason === 'UNAUTHORIZED_DOMAIN') {
        this.snackBar.open(
          'This origin isn\'t in Firebase Auth\'s authorizedDomains list. Ask the admin to add it.',
          'Dismiss',
          {duration: 10000},
        );
        return;
      }
    }
    const msg = (err as {message?: string} | undefined)?.message ?? 'Unknown sign-in error';
    this.snackBar.open(`Couldn't complete sign-in: ${msg}`, 'Dismiss', {duration: 8000});
  }
}
