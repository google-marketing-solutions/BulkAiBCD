import {ComponentFixture, TestBed} from '@angular/core/testing';
import {FormControl} from '@angular/forms';
import {MatDialog} from '@angular/material/dialog';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {of} from 'rxjs';

import {AnalysisConfigComponent} from './analysis-config.component';

describe('AnalysisConfigComponent', () => {
  let fixture: ComponentFixture<AnalysisConfigComponent>;
  let component: AnalysisConfigComponent;
  let dialog: MatDialog;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AnalysisConfigComponent, NoopAnimationsModule],
    }).compileComponents();

    fixture = TestBed.createComponent(AnalysisConfigComponent);
    component = fixture.componentInstance;
    dialog = fixture.debugElement.injector.get(MatDialog);
    fixture.detectChanges();
  });

  it('opens dialog when custom radio option is selected', () => {
    const dialogRefSpy = {
      afterClosed: () => of({long: ['(A) Large Supers'], short: []}),
    };
    spyOn(dialog, 'open').and.returnValue(dialogRefSpy as any);

    component.onRadioChange({value: 'custom', source: {} as any});
    expect(dialog.open).toHaveBeenCalled();
    expect(component.customFeaturesControl().value).toEqual({
      long: ['(A) Large Supers'],
      short: [],
    });
  });

  it('resets control to standard when dialog is closed without selections', () => {
    const dialogRefSpy = {
      afterClosed: () => of(null),
    };
    spyOn(dialog, 'open').and.returnValue(dialogRefSpy as any);

    component.control().setValue('custom');
    component.customFeaturesControl().setValue({long: [], short: []});

    component.openDialog();

    expect(component.control().value).toBe('standard');
  });
});

