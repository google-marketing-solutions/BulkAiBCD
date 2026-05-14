import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';

import {VideoMetadata} from '../../../services/analysis.service';
import {AggregateReportComponent} from './aggregate-report.component';

describe('AggregateReportComponent', () => {
  let fixture: ComponentFixture<AggregateReportComponent>;
  let component: AggregateReportComponent;

  const videos: VideoMetadata[] = [
    {
      id: 'a_v1',
      analysisId: 'a',
      videoId: 'v1',
      videoName: 'https://www.youtube.com/watch?v=abc123',
      sourceType: 'youtube',
      status: 'COMPLETED',
      aScore: 70,
      bScore: 80,
      cScore: 90,
      dScore: 60,
      assetName: 'Ad',
    },
  ];

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AggregateReportComponent, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(AggregateReportComponent);
    component = fixture.componentInstance;
    component.analysisId = 'a1';
    component.videos = videos;
    fixture.detectChanges();
  });

  it('renders the header with asset count', () => {
    const h2 = fixture.nativeElement.querySelector('h2');
    expect(h2.textContent).toContain('Video Breakdown Table');
    expect(h2.textContent).toContain('1');
  });

  it('pitch-deck button is disabled when nothing selected', () => {
    const btn: HTMLButtonElement = fixture.nativeElement.querySelector('button');
    expect(btn.disabled).toBeTrue();
  });

  it('generatePitchDeck opens a new window with the selected rows', () => {
    const fakeDoc = {open: jasmine.createSpy(), write: jasmine.createSpy(), close: jasmine.createSpy()};
    const fakeWin = {document: fakeDoc, focus: jasmine.createSpy(), print: jasmine.createSpy()};
    spyOn(window, 'open').and.returnValue(fakeWin as unknown as Window);
    // Preload a selected row into the child table's emission surface.
    (component as any).selected.set([{
      id: 'x', videoId: 'v', videoName: 'n', thumbnailUrl: null,
      videoLink: null, status: 'COMPLETED', avg: 75, a: 80, b: 70, c: 75, d: 75,
    }]);
    (component as any).generatePitchDeck();
    expect(window.open).toHaveBeenCalled();
    expect(fakeDoc.write).toHaveBeenCalled();
  });
});
