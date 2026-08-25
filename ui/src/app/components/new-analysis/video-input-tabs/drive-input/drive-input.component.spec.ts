import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MatSnackBar} from '@angular/material/snack-bar';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {of, throwError} from 'rxjs';

import {AnalysisService} from '../../../../services/analysis.service';
import {DriveInputComponent} from './drive-input.component';

describe('DriveInputComponent', () => {
  let fixture: ComponentFixture<DriveInputComponent>;
  let component: DriveInputComponent;
  let analysisService: jasmine.SpyObj<AnalysisService>;
  let snackBar: MatSnackBar;

  beforeEach(async () => {
    analysisService = jasmine.createSpyObj<AnalysisService>('AnalysisService', [
      'getConfig',
      'resolveDrive',
    ]);
    analysisService.getConfig.and.returnValue(
      of({
        driveIngestServiceAccount: 'sa@test.iam.gserviceaccount.com',
        projectId: 'test-proj',
      }),
    );

    await TestBed.configureTestingModule({
      imports: [DriveInputComponent, NoopAnimationsModule],
      providers: [{provide: AnalysisService, useValue: analysisService}],
    }).compileComponents();

    fixture = TestBed.createComponent(DriveInputComponent);
    component = fixture.componentInstance;
    snackBar = fixture.debugElement.injector.get(MatSnackBar);
    fixture.detectChanges();
  });

  it('loads service account from config on init', () => {
    expect(component.serviceAccount()).toBe('sa@test.iam.gserviceaccount.com');
  });

  it('validates and adds resolved Drive videos to queue', () => {
    const emitSpy = jasmine.createSpy();
    component.filesAdded.subscribe(emitSpy);

    analysisService.resolveDrive.and.returnValue(
      of({
        videos: [
          {
            videoName: 'Drive Video 1',
            videoUrl: 'https://drive.google.com/file/d/123/view',
            thumbnailUrl: 'https://thumb.url',
          },
        ],
      }),
    );

    component.urlControl.setValue('https://drive.google.com/drive/folders/abc123xyz');
    component.addUrl();

    expect(analysisService.resolveDrive).toHaveBeenCalledWith(
      'https://drive.google.com/drive/folders/abc123xyz',
    );
    expect(emitSpy).toHaveBeenCalled();
    const emittedFiles: File[] = emitSpy.calls.mostRecent().args[0];
    expect(emittedFiles.length).toBe(1);
    expect(emittedFiles[0].name).toBe('Drive Video 1');
    expect(emittedFiles[0].type).toBe('drive/url');
    expect(component.urlControl.value).toBe('');
  });

  it('surfaces a friendly error message when Drive resolve fails', () => {
    const snackSpy = spyOn(snackBar, 'open');
    analysisService.resolveDrive.and.returnValue(
      throwError(() => ({status: 403, error: {code: 'ACCESS_DENIED'}})),
    );

    component.urlControl.setValue('https://drive.google.com/file/d/123/view');
    component.addUrl();

    expect(snackSpy).toHaveBeenCalledWith(
      jasmine.stringMatching(/No access/),
      'Dismiss',
      jasmine.any(Object),
    );
  });
});

