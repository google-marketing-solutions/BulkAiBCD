import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';

import {VideoMetadata} from '../../../services/analysis.service';
import {
  VideoBreakdown,
  VideoBreakdownTableComponent,
} from './video-breakdown-table.component';

describe('VideoBreakdownTableComponent', () => {
  let fixture: ComponentFixture<VideoBreakdownTableComponent>;
  let component: VideoBreakdownTableComponent;

  const videos: VideoMetadata[] = [
    {
      id: 'A_v1',
      analysisId: 'A',
      videoId: 'v1',
      videoName: 'https://www.youtube.com/watch?v=abcdef12345',
      sourceType: 'youtube',
      status: 'COMPLETED',
      aScore: 85,
      bScore: 70,
      cScore: 92,
      dScore: 60,
      assetName: 'Q3 Spot',
    },
    {
      id: 'A_v2',
      analysisId: 'A',
      videoId: 'v2',
      videoName: 'https://youtu.be/zzzzzzzzzzz',
      sourceType: 'youtube',
      status: 'PROCESSING',
    },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [VideoBreakdownTableComponent, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(VideoBreakdownTableComponent);
    component = fixture.componentInstance;
    component.videos = videos;
    fixture.detectChanges();
  });

  it('renders a row per input video', () => {
    expect(component.dataSource.data.length).toBe(2);
  });

  it('computes a YouTube thumbnail URL when the name is a YT link', () => {
    expect(component.dataSource.data[0].thumbnailUrl).toContain('i.ytimg.com');
  });

  it('maps real ABCD scores from the input', () => {
    const row = component.dataSource.data[0];
    expect(row.a).toBe(85);
    expect(row.b).toBe(70);
    expect(row.c).toBe(92);
    expect(row.d).toBe(60);
    expect(row.avg).toBe(Math.round((85 + 70 + 92 + 60) / 4));
  });

  it('maps missing scores to -1 (displayed as dash)', () => {
    const row = component.dataSource.data[1];
    expect(row.a).toBe(-1);
    expect(row.b).toBe(-1);
    expect(row.c).toBe(-1);
    expect(row.d).toBe(-1);
    expect(row.avg).toBe(-1);
  });

  it('fmt() returns a dash for missing scores and the number otherwise', () => {
    expect(component.fmt(-1)).toBe('—');
    expect(component.fmt(75)).toBe(75);
  });

  it('scoreClass() picks colour by range + missing bucket', () => {
    expect(component.scoreClass(-1)).toBe('score-missing');
    expect(component.scoreClass(40)).toBe('score-red');
    expect(component.scoreClass(60)).toBe('score-orange');
    expect(component.scoreClass(80)).toBe('score-yellow');
    expect(component.scoreClass(95)).toBe('score-green');
  });

  it('emits the selected rows via selectionChanged', () => {
    const received: VideoBreakdown[][] = [];
    component.selectionChanged.subscribe((rows) => received.push(rows));
    component.selection.select(component.dataSource.data[0]);
    expect(received[received.length - 1].length).toBe(1);
    component.toggleAllRows();
    expect(received[received.length - 1].length).toBe(2);
  });
});
