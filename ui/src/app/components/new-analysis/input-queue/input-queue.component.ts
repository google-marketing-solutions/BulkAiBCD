import {CommonModule} from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
} from '@angular/core';
import {MatButtonModule} from '@angular/material/button';
import {MatIconModule} from '@angular/material/icon';

interface QueueItem {
  name: string;
  thumbnailUrl: string | null;
  format: string;
  // Index into the original, unsorted File[] input — emitted on delete so the
  // parent can splice the right element out.
  originalIndex: number;
}

interface QueueGroup {
  key: SourceKey;
  label: string;
  icon: string;
  items: QueueItem[];
}

type SourceKey = 'youtube' | 'drive' | 'file' | 'id' | 'other';

const YOUTUBE_ID = /^https?:\/\/(?:www\.)?(?:youtube\.com\/(?:watch\?v=|shorts\/)|youtu\.be\/)([A-Za-z0-9_-]+)/;

const GROUP_ORDER: SourceKey[] = ['youtube', 'drive', 'file', 'id', 'other'];
const GROUP_META: Record<SourceKey, {label: string; icon: string}> = {
  youtube: {label: 'YouTube URLs', icon: 'smart_display'},
  drive:   {label: 'Drive URLs',   icon: 'add_to_drive'},
  file:    {label: 'Uploads',      icon: 'upload_file'},
  id:      {label: 'Ads IDs',      icon: 'badge'},
  other:   {label: 'Other',        icon: 'movie'},
};

@Component({
  selector: 'app-input-queue',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './input-queue.component.html',
  styleUrl: './input-queue.component.scss',
  imports: [CommonModule, MatIconModule, MatButtonModule],
})
export class InputQueueComponent {
  readonly videos = input<File[]>([]);
  readonly deleteVideo = output<number>();
  readonly clearAll = output<void>();

  // Per-group collapse state, keyed by SourceKey. Default expanded.
  private readonly collapsed = signal<Record<SourceKey, boolean>>({
    youtube: false, drive: false, file: false, id: false, other: false,
  });

  readonly groups = computed<QueueGroup[]>(() => {
    const files = this.videos();
    const buckets: Record<SourceKey, QueueItem[]> = {
      youtube: [], drive: [], file: [], id: [], other: [],
    };
    files.forEach((f, i) => {
      const key = sourceKey(f);
      buckets[key].push(toQueueItem(f, i));
    });
    return GROUP_ORDER
      .filter((k) => buckets[k].length > 0)
      .map((k) => ({
        key: k,
        label: GROUP_META[k].label,
        icon: GROUP_META[k].icon,
        items: buckets[k],
      }));
  });

  isCollapsed(key: SourceKey): boolean {
    return this.collapsed()[key];
  }

  toggleGroup(key: SourceKey) {
    this.collapsed.update((c) => ({...c, [key]: !c[key]}));
  }

  onDeleteVideo(originalIndex: number) {
    this.deleteVideo.emit(originalIndex);
  }

  onClearAll() {
    this.clearAll.emit();
  }
}

function sourceKey(file: File): SourceKey {
  switch (file.type) {
    case 'youtube/url':    return 'youtube';
    case 'drive/url':      return 'drive';
    case 'ads/id':         return 'id';
    case 'video/uploaded': return 'file';
    default:               return file.type.startsWith('video/') ? 'file' : 'other';
  }
}

function toQueueItem(file: File, originalIndex: number): QueueItem {
  if (file.type === 'youtube/url') {
    const url = (file as File & {sourceUrl?: string}).sourceUrl ?? file.name;
    const m = YOUTUBE_ID.exec(url);
    if (m) {
      return {
        name: file.name,
        thumbnailUrl: `https://i.ytimg.com/vi/${m[1]}/hqdefault.jpg`,
        originalIndex,
        format: (file as any).format ?? 'LONG',
      };
    }
  }
  const dataUrl = (file as File & {thumbnailDataUrl?: string | null})
    .thumbnailDataUrl;
  const format = (file as any).format ?? 'LONG';
  if (dataUrl) return {name: file.name, thumbnailUrl: dataUrl, originalIndex, format};
  return {name: file.name, thumbnailUrl: null, originalIndex, format};
}
