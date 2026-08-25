/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MatSnackBar} from '@angular/material/snack-bar';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {Router, provideRouter} from '@angular/router';
import {of, throwError} from 'rxjs';

import {AnalysisService, AnalysisVideoFile} from '../../services/analysis.service';
import {NewAnalysisComponent} from './new-analysis.component';

describe('NewAnalysisComponent', () => {
  let fixture: ComponentFixture<NewAnalysisComponent>;
  let component: NewAnalysisComponent;
  let analysisService: jasmine.SpyObj<AnalysisService>;
  let router: Router;
  let snackBar: MatSnackBar;

  beforeEach(async () => {
    analysisService = jasmine.createSpyObj<AnalysisService>('AnalysisService', [
      'submitAnalysis',
      'getConfig',
    ]);
    analysisService.getConfig.and.returnValue(
      of({driveIngestServiceAccount: 'sa@test.iam.gserviceaccount.com', projectId: 'test-project'}),
    );

    await TestBed.configureTestingModule({
      imports: [NewAnalysisComponent, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {provide: AnalysisService, useValue: analysisService},
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(NewAnalysisComponent);
    component = fixture.componentInstance;
    router = TestBed.inject(Router);
    snackBar = fixture.debugElement.injector.get(MatSnackBar);
    fixture.detectChanges();
  });

  function setup(name: string, files: File[]) {
    (component as any).brandName.setValue(name);
    (component as any).addFiles(files);
  }

  it('skips submission when form is invalid', () => {
    (component as any).runAnalysis();
    expect(analysisService.submitAnalysis).not.toHaveBeenCalled();
  });

  it('submits and routes to /list on success', () => {
    analysisService.submitAnalysis.and.returnValue(of('analysis-1'));
    const navigateSpy = spyOn(router, 'navigate').and.returnValue(Promise.resolve(true));
    const file = new File([''], 'v.mp4', {type: 'video/mp4'}) as AnalysisVideoFile;
    file.unlisted = true;
    setup('Q3', [file]);

    (component as any).runAnalysis();

    expect(analysisService.submitAnalysis).toHaveBeenCalledWith(
      jasmine.objectContaining({
        analysisName: 'Q3',
        analysisType: 'standard',
        videos: [jasmine.objectContaining({videoName: 'v.mp4', unlisted: true})],
      }),
    );
    expect(navigateSpy).toHaveBeenCalledWith(['/list']);
  });

  it('surfaces error but stays on page when submit fails', () => {
    analysisService.submitAnalysis.and.returnValue(throwError(() => new Error('nope')));
    const navigateSpy = spyOn(router, 'navigate').and.returnValue(Promise.resolve(true));
    setup('Q3', [new File([''], 'v.mp4', {type: 'video/mp4'})]);

    (component as any).runAnalysis();

    expect(navigateSpy).not.toHaveBeenCalled();
    expect((component as any).submitting()).toBeFalse();
  });

  it('disables marketingObjective when analysisType is set to custom', () => {
    expect((component as any).marketingObjective.enabled).toBeTrue();
    (component as any).analysisType.setValue('custom');
    expect((component as any).marketingObjective.disabled).toBeTrue();

    (component as any).analysisType.setValue('standard');
    expect((component as any).marketingObjective.enabled).toBeTrue();
  });

  it('addFiles caps files to max 25 and attaches format', () => {
    const files: File[] = [];
    for (let i = 0; i < 30; i++) {
      files.push(new File([''], `v${i}.mp4`, {type: 'video/mp4'}));
    }
    const snackSpy = spyOn(snackBar, 'open');

    (component as any).addFiles(files);

    expect((component as any).videos().length).toBe(25);
    expect((component as any).videos()[0].format).toBe('LONG');
    expect(snackSpy).toHaveBeenCalled();
  });

  it('removeVideo removes item by index and clearVideos resets list', () => {
    const f1 = new File([''], 'v1.mp4', {type: 'video/mp4'});
    const f2 = new File([''], 'v2.mp4', {type: 'video/mp4'});
    (component as any).addFiles([f1, f2]);

    expect((component as any).videos().length).toBe(2);

    (component as any).removeVideo(0);
    expect((component as any).videos().length).toBe(1);
    expect((component as any).videos()[0].name).toBe('v2.mp4');

    (component as any).clearVideos();
    expect((component as any).videos().length).toBe(0);
  });
});
