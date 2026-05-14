import {SelectionModel} from '@angular/cdk/collections';
import {CommonModule} from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  EventEmitter,
  Input,
  OnChanges,
  OnInit,
  Output,
  SimpleChanges,
  ViewChild,
  inject,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {MatCheckboxModule} from '@angular/material/checkbox';
import {MatIconModule} from '@angular/material/icon';
import {MatPaginator, MatPaginatorModule} from '@angular/material/paginator';
import {MatTableDataSource, MatTableModule} from '@angular/material/table';

import {VideoMetadata} from '../../../services/analysis.service';

export interface VideoBreakdown {
  id: string;
  videoId: string;
  videoName: string;
  thumbnailUrl: string | null;
  videoLink: string | null;
  sourceLabel: string;
  sourceIcon: string;
  status: string;
  errorMessage: string | null;
  avg: number;
  a: number;
  b: number;
  c: number;
  d: number;
}

const YOUTUBE_ID =
  /^https?:\/\/(?:www\.)?(?:youtube\.com\/watch\?v=|youtu\.be\/)([A-Za-z0-9_-]+)/;

@Component({
  selector: 'app-video-breakdown-table',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './video-breakdown-table.component.html',
  styleUrl: './video-breakdown-table.component.scss',
  imports: [
    CommonModule,
    MatTableModule,
    MatPaginatorModule,
    MatCheckboxModule,
    MatIconModule,
  ],
})
export class VideoBreakdownTableComponent
  implements OnInit, OnChanges, AfterViewInit {
  private readonly destroyRef = inject(DestroyRef);

  @Input() videos: VideoMetadata[] = [];
  @Output() readonly selectionChanged = new EventEmitter<VideoBreakdown[]>();

  @ViewChild(MatPaginator) paginator!: MatPaginator;

  displayedColumns: string[] = [
    'select',
    'thumbnail',
    'videoName',
    'source',
    'status',
    'avg',
    'a',
    'b',
    'c',
    'd',
    'videoLink',
  ];
  readonly dataSource = new MatTableDataSource<VideoBreakdown>([]);
  readonly selection = new SelectionModel<VideoBreakdown>(true, []);

  ngOnInit() {
    this.selection.changed
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.selectionChanged.emit(this.selection.selected));
    this.dataSource.data = this.videos.map(toBreakdown);
  }

  ngAfterViewInit() {
    this.dataSource.paginator = this.paginator;
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['videos']) {
      this.dataSource.data = this.videos.map(toBreakdown);
      this.selection.clear();
    }
  }

  scoreClass(score: number): string {
    if (score < 0) return 'score-missing';
    if (score >= 90) return 'score-green';
    if (score >= 70) return 'score-yellow';
    if (score >= 50) return 'score-orange';
    return 'score-red';
  }

  fmt(score: number): string | number {
    return score < 0 ? '—' : score;
  }

  isAllSelected(): boolean {
    return this.dataSource.data.length > 0 &&
      this.selection.selected.length === this.dataSource.data.length;
  }

  toggleAllRows() {
    if (this.isAllSelected()) {
      this.selection.clear();
    } else {
      this.selection.select(...this.dataSource.data);
    }
  }

  checkboxLabel(row?: VideoBreakdown): string {
    if (!row) return `${this.isAllSelected() ? 'deselect' : 'select'} all`;
    return `${this.selection.isSelected(row) ? 'deselect' : 'select'} row ${row.videoId}`;
  }
}

function toBreakdown(m: VideoMetadata): VideoBreakdown {
  // Real ABCD scores from Gemini. Null when the prompt hasn't landed yet
  // (PROCESSING) or when parsing failed. We map those to -1 and the template
  // renders a dash + a greyed style.
  const a = m.aScore ?? -1;
  const b = m.bScore ?? -1;
  const c = m.cScore ?? -1;
  const d = m.dScore ?? -1;
  const present = [a, b, c, d].filter((n) => n >= 0);
  const avg = present.length === 0
    ? -1
    : Math.round(present.reduce((s, n) => s + n, 0) / present.length);

  const name = m.videoName ?? m.videoId;
  // Prefer the canonical URL recorded at submit time; fall back to videoName
  // for legacy records where the URL itself was stored as the name.
  const urlForLink = m.videoUrl ?? (YOUTUBE_ID.test(name) ? name : null);
  const ytMatch = urlForLink && YOUTUBE_ID.exec(urlForLink);
  // Thumbnail priority: (1) stored thumbnail (captured at upload time for local
  // files), (2) YouTube's canonical still, (3) none → generic icon.
  const thumbnailUrl =
    m.thumbnailUrl ??
    (ytMatch ? `https://i.ytimg.com/vi/${ytMatch[1]}/hqdefault.jpg` : null);
  const videoLink =
    (m.sourceType === 'youtube' || m.sourceType === 'drive') ? urlForLink : null;

  const {sourceLabel, sourceIcon} = describeSource(m.sourceType);

  return {
    id: m.id,
    videoId: m.videoId,
    videoName: name,
    thumbnailUrl,
    videoLink,
    sourceLabel,
    sourceIcon,
    status: m.status,
    errorMessage: m.errorMessage ?? null,
    avg,
    a,
    b,
    c,
    d,
  };
}

function describeSource(src?: string): {sourceLabel: string; sourceIcon: string} {
  switch (src) {
    case 'youtube': return {sourceLabel: 'YouTube', sourceIcon: 'smart_display'};
    case 'drive':   return {sourceLabel: 'Drive',   sourceIcon: 'add_to_drive'};
    case 'file':    return {sourceLabel: 'Upload',  sourceIcon: 'upload_file'};
    case 'id':      return {sourceLabel: 'Ads ID',  sourceIcon: 'badge'};
    default:        return {sourceLabel: '\u2014',  sourceIcon: 'help_outline'};
  }
}
