import {provideHttpClient} from '@angular/common/http';
import {provideHttpClientTesting} from '@angular/common/http/testing';
import {ComponentFixture, TestBed} from '@angular/core/testing';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {provideRouter} from '@angular/router';

import {ListAnalysesComponent} from './list-analyses.component';

describe('ListAnalysesComponent', () => {
  let fixture: ComponentFixture<ListAnalysesComponent>;
  let component: ListAnalysesComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ListAnalysesComponent, NoopAnimationsModule],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    fixture = TestBed.createComponent(ListAnalysesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('creates without error and contains jobs table', () => {
    expect(component).toBeTruthy();
    const tableEl = fixture.nativeElement.querySelector('app-jobs-table');
    expect(tableEl).toBeTruthy();
  });
});

