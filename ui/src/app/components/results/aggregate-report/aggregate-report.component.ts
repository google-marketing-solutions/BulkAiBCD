import {CommonModule} from '@angular/common';
import {ChangeDetectionStrategy, Component, inject, Input, signal} from '@angular/core';
import {MatButtonModule} from '@angular/material/button';
import {MatIconModule} from '@angular/material/icon';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';
import {MatTooltipModule} from '@angular/material/tooltip';

import {AnalysisService, VideoMetadata} from '../../../services/analysis.service';
import {AuthFailure, GoogleAuthService} from '../../../services/google-auth.service';
import {
  VideoBreakdown,
  VideoBreakdownTableComponent,
} from '../video-breakdown-table/video-breakdown-table.component';

@Component({
  selector: 'app-aggregate-report',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './aggregate-report.component.html',
  styleUrl: './aggregate-report.component.scss',
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatTooltipModule,
    VideoBreakdownTableComponent,
  ],
})
export class AggregateReportComponent {
  @Input() analysisId = '';
  @Input() videos: VideoMetadata[] = [];

  private readonly analysisService = inject(AnalysisService);
  private readonly googleAuth = inject(GoogleAuthService);
  private readonly snackBar = inject(MatSnackBar);

  protected readonly selected = signal<VideoBreakdown[]>([]);
  protected readonly generating = signal(false);

  protected onSelectionChanged(rows: VideoBreakdown[]) {
    this.selected.set(rows);
  }

  protected async generatePitchDeck(): Promise<void> {
    if (this.generating() || !this.analysisId) return;
    const rows = this.selected();
    if (rows.length === 0) return;
    const videoIds = rows.map((r) => r.videoId);

    let token: string;
    try {
      token = await this.googleAuth.requestDriveToken();
    } catch (err) {
      this.showAuthSnackbar(err);
      return;
    }

    this.generating.set(true);
    this.analysisService.generatePitchDeck(this.analysisId, videoIds, token).subscribe({
      next: (res) => {
        this.generating.set(false);
        const decks = res?.decks ?? [];
        if (decks.length === 0) {
          this.snackBar.open(
            'Deck request succeeded but no URLs were returned.',
            'Dismiss',
            {duration: 6000},
          );
          return;
        }
        for (const d of decks) {
          window.open(d.deckUrl, '_blank', 'noopener');
        }
      },
      error: (err) => {
        this.generating.set(false);
        const msg = err?.error?.error ?? err?.message ?? 'Unknown error';
        this.snackBar.open(`Deck generation failed: ${msg}`, 'Dismiss', {duration: 10000});
      },
    });
  }

  private showAuthSnackbar(err: unknown): void {
    if (err instanceof AuthFailure) {
      if (err.reason === 'USER_DISMISSED') {
        this.snackBar.open(
          'Pitch deck needs permission to save to your Drive. Click the button again to approve.',
          'Dismiss',
          {duration: 8000},
        );
        return;
      }
      if (err.reason === 'POPUP_BLOCKED') {
        this.snackBar.open(
          'Your browser blocked the sign-in popup. Allow popups for this site, then click Pitch Deck again.',
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
