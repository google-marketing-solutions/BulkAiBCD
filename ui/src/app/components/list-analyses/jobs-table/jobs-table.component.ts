import {MatDialog, MatDialogModule} from '@angular/material/dialog';
import {AsyncPipe, CommonModule, DatePipe} from '@angular/common';
import {ChangeDetectionStrategy, Component, DestroyRef, inject, OnInit, TemplateRef, ViewChild} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {MatButtonModule} from '@angular/material/button';
import {MatIconModule} from '@angular/material/icon';
import {MatProgressSpinnerModule} from '@angular/material/progress-spinner';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';
import {MatTableModule} from '@angular/material/table';
import {MatTooltipModule} from '@angular/material/tooltip';
import {RouterModule} from '@angular/router';
import {BehaviorSubject, catchError, of, Subscription, tap, timer} from 'rxjs';

import {AnalysisRequest, AnalysisService} from '../../../services/analysis.service';

export type JobStatus = 'Completed' | 'Processing' | 'Cancelled';

export interface Job {
  analysisId: string;
  analysisName: string;
  analysisType: string;
  status: JobStatus;
  dateCreated: Date | null;
  customFeatures: string[];
}

const DEFAULT_REQUESTER_ID = 'default-user';
const POLL_MS = 5000;

@Component({
  selector: 'app-jobs-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './jobs-table.component.html',
  styleUrl: './jobs-table.component.scss',
  imports: [
    CommonModule,
    MatTableModule,
    MatIconModule,
    MatButtonModule,
    MatProgressSpinnerModule,
    MatSnackBarModule,
    MatTooltipModule,
    RouterModule,
    DatePipe,
    AsyncPipe,
    MatDialogModule,
  ],
})
export class JobsTableComponent implements OnInit {
  private readonly analysisService = inject(AnalysisService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dialog = inject(MatDialog);

  readonly displayedColumns: string[] = [
    'analysisName',
    'analysisType',
    'status',
    'dateCreated',
    'action',
    'manage',
  ];

  readonly dataSource$ = new BehaviorSubject<Job[]>([]);
  readonly loading$ = new BehaviorSubject<boolean>(false);
  readonly error$ = new BehaviorSubject<string | null>(null);

  private pollSub: Subscription | null = null;

 @ViewChild('featuresDialogTemplate')
  featuresDialogTemplate!: TemplateRef<string[]>;

  ngOnInit() {
    this.loading$.next(true);
    this.reload();
  }

  private reload() {
    // No "refreshing" flag — the per-row "In Progress" spinner in the Action
    // column is the continuous indicator. Surfacing a second pulsing indicator
    // on every 5s poll caused the whole table to jitter up and down.
    this.analysisService
      .listAnalyses(DEFAULT_REQUESTER_ID)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        tap((rows: AnalysisRequest[]) => {
          const jobs = rows.map(toJob);
          this.dataSource$.next(jobs);
          this.loading$.next(false);
          this.error$.next(null);
          this.schedulePoll(jobs);
        }),
        catchError((err) => {
          this.error$.next(err?.message ?? 'Failed to load analyses');
          this.loading$.next(false);
          this.pollSub?.unsubscribe();
          this.pollSub = timer(POLL_MS)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe(() => this.reload());
          return of(null);
        }),
      )
      .subscribe();
  }

  openFeaturesDialog(features: string[]) {
    this.dialog.open(this.featuresDialogTemplate, {
      data: features,
      width: '700px'
    });
  }

  private schedulePoll(jobs: Job[]) {
    this.pollSub?.unsubscribe();
    this.pollSub = null;
    const anyProcessing = jobs.some((j) => j.status === 'Processing');
    if (!anyProcessing) return;
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return;
    this.pollSub = timer(POLL_MS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.reload());
  }

  cancel(job: Job) {
    if (!confirm(`Cancel "${job.analysisName}"? In-flight Gemini calls will stop.`)) return;
    this.analysisService.cancelAnalysis(job.analysisId).subscribe({
      next: () => {
        this.snackBar.open('Cancelled.', 'Dismiss', {duration: 3000});
        this.reload();
      },
      error: (err) =>
        this.snackBar.open(
          `Cancel failed: ${err?.message ?? 'unknown error'}`,
          'Dismiss',
          {duration: 6000},
        ),
    });
  }

  delete(job: Job) {
    if (
      !confirm(
        `Delete "${job.analysisName}"? This removes the analysis, its metadata, and any uploaded videos. Cannot be undone.`,
      )
    ) {
      return;
    }
    this.analysisService.deleteAnalysis(job.analysisId).subscribe({
      next: () => {
        this.snackBar.open('Deleted.', 'Dismiss', {duration: 3000});
        this.reload();
      },
      error: (err) =>
        this.snackBar.open(
          `Delete failed: ${err?.message ?? 'unknown error'}`,
          'Dismiss',
          {duration: 6000},
        ),
    });
  }
}

function toJob(row: AnalysisRequest): Job {
  return {
    analysisId: row.analysisId ?? '',
    analysisName: row.analysisName,
    analysisType: row.analysisType,
    status: statusOf(row.analysisStatus),
    dateCreated: row.createdAt ? new Date(row.createdAt) : null,
    customFeatures: row.customFeatures ?? [],
  };
}

function statusOf(raw: string | undefined): JobStatus {
  switch (raw) {
    case 'COMPLETED': return 'Completed';
    case 'CANCELLED': return 'Cancelled';
    default:          return 'Processing';
  }
}