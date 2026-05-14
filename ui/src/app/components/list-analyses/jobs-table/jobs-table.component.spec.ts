import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {provideRouter} from '@angular/router';
import {of, throwError} from 'rxjs';

import {AnalysisRequest, AnalysisService} from '../../../services/analysis.service';
import {JobsTableComponent} from './jobs-table.component';

describe('JobsTableComponent', () => {
  let fixture: ComponentFixture<JobsTableComponent>;
  let component: JobsTableComponent;
  let analysisService: jasmine.SpyObj<AnalysisService>;

  beforeEach(async () => {
    analysisService = jasmine.createSpyObj<AnalysisService>('AnalysisService', [
      'listAnalyses',
    ]);
    await TestBed.configureTestingModule({
      imports: [JobsTableComponent, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        {provide: AnalysisService, useValue: analysisService},
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(JobsTableComponent);
    component = fixture.componentInstance;
  });

  it('loads rows on init and maps AnalysisRequest to Job shape', () => {
    const rows: AnalysisRequest[] = [
      {
        analysisId: 'a1',
        requesterId: 'u',
        analysisName: 'Q3',
        analysisType: 'standard',
        analysisStatus: 'COMPLETED',
        createdAt: '2026-01-01T00:00:00Z',
      },
      {
        analysisId: 'a2',
        requesterId: 'u',
        analysisName: 'Q4',
        analysisType: 'light',
        analysisStatus: 'PROCESSING',
      },
    ];
    analysisService.listAnalyses.and.returnValue(of(rows));

    fixture.detectChanges();

    const jobs = component.dataSource$.value;
    expect(jobs.length).toBe(2);
    expect(jobs[0].analysisId).toBe('a1');
    expect(jobs[0].status).toBe('Completed');
    expect(jobs[1].status).toBe('Processing');
    expect(jobs[0].dateCreated).toEqual(new Date('2026-01-01T00:00:00Z'));
    expect(component.loading$.value).toBeFalse();
    expect(component.error$.value).toBeNull();
  });

  it('surfaces an error message when the service fails', () => {
    analysisService.listAnalyses.and.returnValue(
      throwError(() => new Error('boom')),
    );
    fixture.detectChanges();

    expect(component.dataSource$.value).toEqual([]);
    expect(component.loading$.value).toBeFalse();
    expect(component.error$.value).toBe('boom');
  });

  it('maps non-COMPLETED status values to Processing', () => {
    const rows: AnalysisRequest[] = [
      {
        analysisId: 'a',
        requesterId: 'u',
        analysisName: 'n',
        analysisType: 't',
        analysisStatus: 'PENDING',
      },
    ];
    analysisService.listAnalyses.and.returnValue(of(rows));
    fixture.detectChanges();
    expect(component.dataSource$.value[0].status).toBe('Processing');
  });
});
