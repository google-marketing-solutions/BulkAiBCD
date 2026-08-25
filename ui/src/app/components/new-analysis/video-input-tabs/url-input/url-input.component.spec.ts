import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MatSnackBar} from '@angular/material/snack-bar';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';

import {UrlInputComponent} from './url-input.component';

describe('UrlInputComponent', () => {
  let fixture: ComponentFixture<UrlInputComponent>;
  let component: UrlInputComponent;
  let snackBar: MatSnackBar;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UrlInputComponent, NoopAnimationsModule],
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

  it('validates and emits YouTube URL files', async () => {
    const emitSpy = jasmine.createSpy();
    component.filesAdded.subscribe(emitSpy);

    spyOn(window, 'fetch').and.returnValue(
      Promise.resolve(new Response(JSON.stringify({title: 'Sample Video'}))),
    );

    component.urlsControl.setValue(
      'https://www.youtube.com/watch?v=dQw4w9WgXcQ\nhttps://youtube.com/shorts/abc12345',
    );

    await component.addUrls();

    expect(emitSpy).toHaveBeenCalled();
    const emitted: File[] = emitSpy.calls.mostRecent().args[0];
    expect(emitted.length).toBe(2);
    expect(emitted[0].type).toBe('youtube/url');
    expect((emitted[0] as any).sourceUrl).toBe('https://www.youtube.com/watch?v=dQw4w9WgXcQ');
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
});

