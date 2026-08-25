import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MAT_DIALOG_DATA, MatDialogRef} from '@angular/material/dialog';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';

import {
  CustomFeaturesDialogComponent,
  DialogData,
} from './custom-features-dialog.component';

describe('CustomFeaturesDialogComponent', () => {
  let fixture: ComponentFixture<CustomFeaturesDialogComponent>;
  let component: CustomFeaturesDialogComponent;
  let dialogRef: jasmine.SpyObj<MatDialogRef<CustomFeaturesDialogComponent>>;

  const initialData: DialogData = {
    selectedFeaturesLong: ['(A) Large Supers'],
    selectedFeaturesShort: ['(A) Voice'],
  };

  beforeEach(async () => {
    dialogRef = jasmine.createSpyObj<MatDialogRef<CustomFeaturesDialogComponent>>(
      'MatDialogRef',
      ['close'],
    );

    await TestBed.configureTestingModule({
      imports: [CustomFeaturesDialogComponent, NoopAnimationsModule],
      providers: [
        {provide: MatDialogRef, useValue: dialogRef},
        {provide: MAT_DIALOG_DATA, useValue: initialData},
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CustomFeaturesDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('initializes selected feature sets from dialog data', () => {
    expect(component.isSelectedLong('(A) Large Supers')).toBeTrue();
    expect(component.isSelectedShort('(A) Voice')).toBeTrue();
    expect(component.isSelectedLong('(B) Brand Logo (Large)')).toBeFalse();
  });

  it('filters items correctly based on searchQuery', () => {
    component.searchQuery = 'Supers';
    const filtered = component.getFiltered(['(A) Large Supers', '(B) Brand Logo']);
    expect(filtered).toEqual(['(A) Large Supers']);
  });

  it('toggles individual long and short features', () => {
    component.toggleFeatureLong('(B) Brand Visual', true);
    expect(component.isSelectedLong('(B) Brand Visual')).toBeTrue();
    component.toggleFeatureLong('(B) Brand Visual', false);
    expect(component.isSelectedLong('(B) Brand Visual')).toBeFalse();

    component.toggleFeatureShort('(C) Emoji Usage', true);
    expect(component.isSelectedShort('(C) Emoji Usage')).toBeTrue();
    component.toggleFeatureShort('(C) Emoji Usage', false);
    expect(component.isSelectedShort('(C) Emoji Usage')).toBeFalse();
  });

  it('toggles all long features', () => {
    component.toggleAllLong(true);
    expect(component.isAllLongSelected()).toBeTrue();
    component.toggleAllLong(false);
    expect(component.isAllLongSelected()).toBeFalse();
  });

  it('toggles all short features', () => {
    component.toggleAllShorts(true);
    expect(component.isAllShortsSelected()).toBeTrue();
    component.toggleAllShorts(false);
    expect(component.isAllShortsSelected()).toBeFalse();
  });

  it('onCancel closes without data and onSave closes with selected arrays', () => {
    component.onCancel();
    expect(dialogRef.close).toHaveBeenCalledWith();

    component.onSave();
    expect(dialogRef.close).toHaveBeenCalledWith(
      jasmine.objectContaining({
        long: jasmine.any(Array),
        short: jasmine.any(Array),
      }),
    );
  });
});

