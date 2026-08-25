import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {provideRouter} from '@angular/router';

import {VideoInputTabsComponent} from './video-input-tabs.component';

describe('VideoInputTabsComponent', () => {
  let fixture: ComponentFixture<VideoInputTabsComponent>;
  let component: VideoInputTabsComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [VideoInputTabsComponent, NoopAnimationsModule],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(VideoInputTabsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('creates successfully with sub-tab components', () => {
    expect(component).toBeTruthy();
    const compiled = fixture.nativeElement;
    expect(compiled.querySelector('mat-tab-group')).toBeTruthy();
  });
});
