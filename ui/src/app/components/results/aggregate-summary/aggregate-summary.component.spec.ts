import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MatSnackBar} from '@angular/material/snack-bar';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {of, throwError} from 'rxjs';

import {AnalysisService, VideoMetadata} from '../../../services/analysis.service';
import {AuthFailure, GoogleAuthService} from '../../../services/google-auth.service';
import {AggregateSummaryComponent} from './aggregate-summary.component';

describe('AggregateSummaryComponent', () => {
  let fixture: ComponentFixture<AggregateSummaryComponent>;
  let component: AggregateSummaryComponent;
  let analysisService: jasmine.SpyObj<AnalysisService>;
  let googleAuth: jasmine.SpyObj<GoogleAuthService>;
  let snackBar: MatSnackBar;

  const mockVideos: VideoMetadata[] = [
    {
      id: '1',
      analysisId: 'ana-1',
      videoId: 'v1',
      status: 'COMPLETED',
      aScore: 80,
      bScore: 90,
      cScore: 70,
      dScore: 80,
    },
    {
      id: '2',
      analysisId: 'ana-1',
      videoId: 'v2',
      status: 'COMPLETED',
      aScore: 100,
      bScore: 100,
      cScore: 80,
      dScore: 80,
    },
  ];

  beforeEach(async () => {
    analysisService = jasmine.createSpyObj<AnalysisService>('AnalysisService', [
      'generateSpreadsheet',
    ]);
    googleAuth = jasmine.createSpyObj<GoogleAuthService>('GoogleAuthService', [
      'requestDriveToken',
    ]);

    await TestBed.configureTestingModule({
      imports: [AggregateSummaryComponent, NoopAnimationsModule],
      providers: [
        {provide: AnalysisService, useValue: analysisService},
        {provide: GoogleAuthService, useValue: googleAuth},
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AggregateSummaryComponent);
    component = fixture.componentInstance;
    snackBar = fixture.debugElement.injector.get(MatSnackBar);
    fixture.detectChanges();
  });

  it('computes average score across all videos accurately', () => {
    fixture.componentRef.setInput('videos', mockVideos);
    fixture.detectChanges();

    // v1 avg = 80, v2 avg = 90 -> overall avg = 85
    expect(component.avgScore()).toBe(85);
  });

  it('maps marketing objective to human-readable label', () => {
    fixture.componentRef.setInput('marketingObjective', 'awareness');
    fixture.detectChanges();
    expect(component.objectiveLabel()).toBe('Awareness');

    fixture.componentRef.setInput('marketingObjective', 'core_unknown');
    fixture.detectChanges();
    expect(component.objectiveLabel()).toBe('Core/Unknown');
  });

  it('returns appropriate score CSS class based on numeric range', () => {
    expect(component.scoreClass(95)).toBe('score-green');
    expect(component.scoreClass(75)).toBe('score-yellow');
    expect(component.scoreClass(55)).toBe('score-orange');
    expect(component.scoreClass(30)).toBe('score-red');
  });

  it('openDetailedSpreadsheet requests token and generates sheet in Google Drive', async () => {
    googleAuth.requestDriveToken.and.returnValue(Promise.resolve('mock-token'));
    analysisService.generateSpreadsheet.and.returnValue(
      of({sheetUrl: 'https://docs.google.com/spreadsheets/d/123'}),
    );
    spyOn(window, 'open');

    fixture.componentRef.setInput('analysisId', 'ana-1');
    fixture.detectChanges();

    await component.openDetailedSpreadsheet();

    expect(googleAuth.requestDriveToken).toHaveBeenCalled();
    expect(analysisService.generateSpreadsheet).toHaveBeenCalledWith(
      'ana-1',
      'mock-token',
    );
    expect(window.open).toHaveBeenCalledWith(
      'https://docs.google.com/spreadsheets/d/123',
      '_blank',
      'noopener',
    );
  });

  it('surfaces a friendly auth snackbar when user dismisses Drive permission', async () => {
    const snackSpy = spyOn(snackBar, 'open');
    googleAuth.requestDriveToken.and.rejectWith(
      new AuthFailure('USER_DISMISSED', 'dismissed'),
    );

    fixture.componentRef.setInput('analysisId', 'ana-1');
    fixture.detectChanges();

    await component.openDetailedSpreadsheet();

    expect(snackSpy).toHaveBeenCalledWith(
      jasmine.stringMatching(/needs permission/),
      'Dismiss',
      jasmine.any(Object),
    );
  });
});

