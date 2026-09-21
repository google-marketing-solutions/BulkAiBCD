import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MatSnackBar} from '@angular/material/snack-bar';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {of} from 'rxjs';

import {AnalysisService, AnalysisVideoFile} from '../../../../services/analysis.service';
import {UrlInputComponent} from './url-input.component';

describe('UrlInputComponent', () => {
  let fixture: ComponentFixture<UrlInputComponent>;
  let component: UrlInputComponent;
  let snackBar: MatSnackBar;
  let analysisService: jasmine.SpyObj<AnalysisService>;

  beforeEach(async () => {
    analysisService = jasmine.createSpyObj<AnalysisService>('AnalysisService', [
      'resolveYouTubeUrls',
    ]);

    await TestBed.configureTestingModule({
      imports: [UrlInputComponent, NoopAnimationsModule],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        {provide: AnalysisService, useValue: analysisService},
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(UrlInputComponent);
    component = fixture.componentInstance;
    snackBar = fixture.debugElement.injector.get(MatSnackBar);
    fixture.detectChanges();
  });

  it('lineCount counts valid lines accurately', () => {
    component.urlsControl.setValue('https://youtube.com/watch?v=1\n\nhttps://youtu.be/2');
    expect(component.lineCount()).toBe(2);

    component.urlsControl.setValue('');
    expect(component.lineCount()).toBe(0);
  });

  it('validates and emits YouTube URL files with unlisted detection', async () => {
    const emitSpy = jasmine.createSpy();
    component.filesAdded.subscribe(emitSpy);

    analysisService.resolveYouTubeUrls.and.returnValue(
      of([
        {
          videoId: 'dQw4w9WgXcQ',
          url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
          title: 'Sample Video',
          unlisted: true,
        },
        {
          videoId: 'abc12345',
          url: 'https://youtube.com/shorts/abc12345',
          title: 'Short Video',
          unlisted: false,
        },
      ]),
    );

    component.urlsControl.setValue(
      'https://www.youtube.com/watch?v=dQw4w9WgXcQ\nhttps://youtube.com/shorts/abc12345',
    );

    await component.addUrls();

    expect(emitSpy).toHaveBeenCalled();
    const emitted: AnalysisVideoFile[] = emitSpy.calls.mostRecent().args[0];
    expect(emitted.length).toBe(2);
    expect(emitted[0].type).toBe('youtube/url');
    expect(emitted[0].sourceUrl).toBe('https://www.youtube.com/watch?v=dQw4w9WgXcQ');
    expect(emitted[0].unlisted).toBeTrue();
    expect(emitted[1].unlisted).toBeFalse();
    expect(component.urlsControl.value).toBe('');
  });

  it('skips invalid non-YouTube URLs and surfaces a snackbar', async () => {
    const snackSpy = spyOn(snackBar, 'open');
    const emitSpy = jasmine.createSpy();
    component.filesAdded.subscribe(emitSpy);

    component.urlsControl.setValue('https://not-youtube.com/video');
    await component.addUrls();

    expect(emitSpy).not.toHaveBeenCalled();
    expect(snackSpy).toHaveBeenCalledWith(
      jasmine.stringMatching(/Skipped 1 invalid URL/),
      'Dismiss',
      jasmine.any(Object),
    );
  });

  it('skips unlisted YouTube URLs when backend returns error_message and surfaces a snackbar', async () => {
    const snackSpy = spyOn(snackBar, 'open');
    const emitSpy = jasmine.createSpy();
    component.filesAdded.subscribe(emitSpy);

    analysisService.resolveYouTubeUrls.and.returnValue(
      of([
        {
          videoId: 'dQw4w9WgXcQ',
          url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ',
          title: 'Unlisted Video',
          unlisted: true,
          error_message: 'Unlisted YouTube videos are not supported.',
        },
        {
          videoId: 'abc12345',
          url: 'https://youtube.com/shorts/abc12345',
          title: 'Public Video',
          unlisted: false,
        },
      ]),
    );

    component.urlsControl.setValue(
      'https://www.youtube.com/watch?v=dQw4w9WgXcQ\nhttps://youtube.com/shorts/abc12345',
    );

    await component.addUrls();

    expect(emitSpy).toHaveBeenCalled();
    const emitted: AnalysisVideoFile[] = emitSpy.calls.mostRecent().args[0];
    expect(emitted.length).toBe(1);
    expect(emitted[0].sourceUrl).toBe('https://youtube.com/shorts/abc12345');
    expect(snackSpy).toHaveBeenCalledWith(
      'Skipped 1 unlisted video: unlisted videos are not supported.',
      'Dismiss',
      jasmine.any(Object),
    );
  });
});
