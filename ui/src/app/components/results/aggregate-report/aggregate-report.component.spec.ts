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

import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MatSnackBar} from '@angular/material/snack-bar';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {of} from 'rxjs';

import {AnalysisService, VideoMetadata} from '../../../services/analysis.service';
import {AuthFailure, GoogleAuthService} from '../../../services/google-auth.service';
import {AggregateReportComponent} from './aggregate-report.component';

describe('AggregateReportComponent', () => {
  let fixture: ComponentFixture<AggregateReportComponent>;
  let component: AggregateReportComponent;
  let analysisService: jasmine.SpyObj<AnalysisService>;
  let googleAuth: jasmine.SpyObj<GoogleAuthService>;
  let snackBar: MatSnackBar;

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
    analysisService = jasmine.createSpyObj<AnalysisService>('AnalysisService', [
      'generatePitchDeck',
    ]);
    googleAuth = jasmine.createSpyObj<GoogleAuthService>('GoogleAuthService', [
      'requestDriveToken',
    ]);

    await TestBed.configureTestingModule({
      imports: [AggregateReportComponent, NoopAnimationsModule],
      providers: [
        {provide: AnalysisService, useValue: analysisService},
        {provide: GoogleAuthService, useValue: googleAuth},
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AggregateReportComponent);
    component = fixture.componentInstance;
    snackBar = fixture.debugElement.injector.get(MatSnackBar);
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

  it('generatePitchDeck requests token and generates slides presentation in Drive', async () => {
    googleAuth.requestDriveToken.and.returnValue(Promise.resolve('mock-token'));
    analysisService.generatePitchDeck.and.returnValue(
      of({
        decks: [
          {
            videoId: 'v1',
            videoTitle: 'Ad 1',
            deckUrl: 'https://docs.google.com/presentation/d/deck-123',
          },
        ],
      }),
    );
    spyOn(window, 'open');

    // Select row
    (component as any).selected.set([
      {
        id: 'x',
        videoId: 'v1',
        videoName: 'Ad 1',
        thumbnailUrl: null,
        videoLink: null,
        status: 'COMPLETED',
        avg: 75,
        a: 80,
        b: 70,
        c: 75,
        d: 75,
      },
    ]);

    await (component as any).generatePitchDeck();

    expect(googleAuth.requestDriveToken).toHaveBeenCalled();
    expect(analysisService.generatePitchDeck).toHaveBeenCalledWith(
      'a1',
      ['v1'],
      'mock-token',
    );
    expect(window.open).toHaveBeenCalledWith(
      'https://docs.google.com/presentation/d/deck-123',
      '_blank',
      'noopener',
    );
  });

  it('surfaces auth error when popup is blocked', async () => {
    const snackSpy = spyOn(snackBar, 'open');
    googleAuth.requestDriveToken.and.rejectWith(
      new AuthFailure('POPUP_BLOCKED', 'blocked'),
    );

    (component as any).selected.set([
      {
        id: 'x',
        videoId: 'v1',
        videoName: 'Ad 1',
        thumbnailUrl: null,
        videoLink: null,
        status: 'COMPLETED',
        avg: 75,
        a: 80,
        b: 70,
        c: 75,
        d: 75,
      },
    ]);

    await (component as any).generatePitchDeck();

    expect(snackSpy).toHaveBeenCalledWith(
      jasmine.stringMatching(/blocked the sign-in popup/),
      'Dismiss',
      jasmine.any(Object),
    );
  });
});
