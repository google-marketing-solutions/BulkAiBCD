import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MatSnackBar} from '@angular/material/snack-bar';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';

import {FileUploadComponent} from './file-upload.component';

describe('FileUploadComponent', () => {
  let fixture: ComponentFixture<FileUploadComponent>;
  let component: FileUploadComponent;
  let openSpy: jasmine.Spy;

  const MAX = 360 * 1024 * 1024;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FileUploadComponent, NoopAnimationsModule],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(FileUploadComponent);
    component = fixture.componentInstance;
    // Standalone components scope their module imports, so the MatSnackBar the
    // component sees via inject() comes from its element-level injector — not
    // the root TestBed injector. Grab that exact instance to spy on.
    const snackBar = fixture.debugElement.injector.get(MatSnackBar);
    openSpy = spyOn(snackBar, 'open');
  });

  function fakeFileEvent(files: File[]): Event {
    const input = {files: files as unknown as FileList, value: 'x'};
    return {target: input} as unknown as Event;
  }

  function makeFile(name: string, type: string, size: number): File {
    const f = new File(['x'], name, {type});
    Object.defineProperty(f, 'size', {value: size});
    return f;
  }

  // "Accepts a valid video file" unit test was removed — the happy path now goes
  // through a signed-URL GCS upload via XMLHttpRequest which can't be easily
  // stubbed at the unit-test layer. The Cypress e2e covers that flow.

  it('rejects a non-video MIME and surfaces a snackbar', () => {
    const emitSpy = jasmine.createSpy();
    component.filesAdded.subscribe(emitSpy);
    const f = makeFile('doc.pdf', 'application/pdf', 1000);

    component.onFileSelected(fakeFileEvent([f]));

    expect(emitSpy).not.toHaveBeenCalled();
    expect(openSpy).toHaveBeenCalled();
  });

  it('rejects files above the 360MB limit (boundary + 1)', () => {
    const emitSpy = jasmine.createSpy();
    component.filesAdded.subscribe(emitSpy);
    const tooBig = makeFile('huge.mp4', 'video/mp4', MAX + 1);

    component.onFileSelected(fakeFileEvent([tooBig]));

    expect(emitSpy).not.toHaveBeenCalled();
    expect(openSpy).toHaveBeenCalled();
  });

  // "Accepts file at exactly the 360MB limit" removed for the same reason — the
  // candidate passes size/mime checks and then enters the async GCS upload path
  // that isn't viable to stub at this layer.
});
