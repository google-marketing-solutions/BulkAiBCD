import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {ActivatedRoute, convertToParamMap, provideRouter} from '@angular/router';
import {of} from 'rxjs';

import {AnalysisService} from '../../services/analysis.service';
import {ResultsComponent} from './results.component';

describe('ResultsComponent', () => {
  let fixture: ComponentFixture<ResultsComponent>;
  let analysisService: jasmine.SpyObj<AnalysisService>;

  beforeEach(async () => {
    analysisService = jasmine.createSpyObj<AnalysisService>('AnalysisService', [
      'getAnalysisDetails',
      'getVideoMetadata',
    ]);
    analysisService.getAnalysisDetails.and.returnValue(
      of({
        analysisId: 'abc',
        requesterId: 'u',
        analysisName: 'Q3 Campaign',
        analysisType: 'standard',
        brandName: 'Acme',
        marketingObjective: 'core_unknown',
        analysisStatus: 'COMPLETED',
      }),
    );
    analysisService.getVideoMetadata.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [ResultsComponent, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        {provide: AnalysisService, useValue: analysisService},
        {
          provide: ActivatedRoute,
          useValue: {snapshot: {paramMap: convertToParamMap({analysisId: 'abc'})}},
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ResultsComponent);
    fixture.detectChanges();
  });

  it('reads analysisId from the route snapshot', () => {
    expect((fixture.componentInstance as any).analysisId).toBe('abc');
  });

  it('loads both analysis details and video metadata on init', () => {
    expect(analysisService.getAnalysisDetails).toHaveBeenCalledWith('abc');
    expect(analysisService.getVideoMetadata).toHaveBeenCalledWith('abc');
  });

  it('renders the report title with brand name', () => {
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Acme');
    expect(text).toContain('Report');
  });
});
