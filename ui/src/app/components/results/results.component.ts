import {CommonModule} from '@angular/common';
import {ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {MatButtonModule} from '@angular/material/button';
import {MatIconModule} from '@angular/material/icon';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {ActivatedRoute, RouterModule} from '@angular/router';
import {Subscription, catchError, forkJoin, of, tap, timer} from 'rxjs';

import {
  AnalysisRequest,
  AnalysisService,
  VideoMetadata,
} from '../../services/analysis.service';
import {AggregateReportComponent} from './aggregate-report/aggregate-report.component';
import {AggregateSummaryComponent} from './aggregate-summary/aggregate-summary.component';

/** Poll every 5s while at least one video is still PROCESSING. */
const POLL_MS = 5000;

@Component({
  selector: 'app-results',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './results.component.html',
  styleUrl: './results.component.scss',
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    RouterModule,
    AggregateSummaryComponent,
    AggregateReportComponent,
  ],
})
export class ResultsComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly analysisService = inject(AnalysisService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly analysisId = this.route.snapshot.paramMap.get('analysisId') ?? '';
  protected readonly analysis = signal<AnalysisRequest | null>(null);
  protected readonly videos = signal<VideoMetadata[]>([]);
  protected readonly loading = signal<boolean>(true);
  protected readonly refreshing = signal<boolean>(false);
  protected readonly error = signal<string | null>(null);

  private pollSub: Subscription | null = null;

  formattedDate(): string {
    const created = this.analysis()?.createdAt;
    const d = created ? new Date(created) : new Date();
    if (isNaN(d.getTime())) return '';
    const date = d.toLocaleDateString('en-US', {month: '2-digit', day: '2-digit', year: 'numeric'});
    const time = d.toLocaleTimeString('en-US', {hour: '2-digit', minute: '2-digit', hour12: false});
    return `${date}, ${time}`;
  }

  ngOnInit() {
    if (!this.analysisId) {
      this.error.set('No analysis ID');
      this.loading.set(false);
      return;
    }
    this.refresh(/*silent=*/ false);
  }

  refresh(silent = true) {
    if (silent) this.refreshing.set(true);
    else this.loading.set(true);

    forkJoin({
      analysis: this.analysisService.getAnalysisDetails(this.analysisId),
      videos: this.analysisService.getVideoMetadata(this.analysisId),
    })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        tap(({analysis, videos}) => {
          this.analysis.set(analysis);
          this.videos.set(videos);
          this.loading.set(false);
          this.refreshing.set(false);
          this.error.set(null);
          this.schedulePoll(videos);
        }),
        catchError((err) => {
          // Keep existing data on transient errors; reschedule a retry.
          this.error.set(err?.message ?? 'Failed to load analysis');
          this.loading.set(false);
          this.refreshing.set(false);
          this.pollSub?.unsubscribe();
          this.pollSub = timer(POLL_MS)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.refresh(true));
          return of(null);
        }),
      )
      .subscribe();
  }

  private schedulePoll(videos: VideoMetadata[]) {
    const anyProcessing = videos.some((v) => v.status !== 'COMPLETED');
    // Cancel any previous poll before deciding whether to start a new one.
    this.pollSub?.unsubscribe();
    this.pollSub = null;
    if (!anyProcessing) return;
    // Don't poll when the tab is hidden — resumes when the user comes back.
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
    this.pollSub = timer(POLL_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh(true));
  }
}
