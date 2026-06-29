import {ChangeDetectionStrategy, ChangeDetectorRef, Component, inject, input} from '@angular/core';
import {FormControl, ReactiveFormsModule} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatDialog, MatDialogModule} from '@angular/material/dialog';
import {MatIconModule} from '@angular/material/icon';
import {MatRadioChange, MatRadioModule} from '@angular/material/radio';
import {MatTooltipModule} from '@angular/material/tooltip';
import {CustomFeaturesDialogComponent} from './custom-features-dialog.component';

@Component({
  selector: 'app-analysis-config',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './analysis-config.component.html',
  styleUrl: './analysis-config.component.scss',
  imports: [
    MatRadioModule,
    MatIconModule,
    MatTooltipModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
  ],
})
export class AnalysisConfigComponent {
  // Supplied by the parent so the submit handler can read the current value.
  // Falls back to a local control if the parent ever renders this without one.
  readonly control = input<FormControl<string | null>>(
    new FormControl<string | null>('standard'),
  );

  readonly customFeaturesControl = input<FormControl<string[] | null>>(
    new FormControl<string[]>([])
  );

  private readonly dialog = inject(MatDialog);
  private readonly cdr = inject(ChangeDetectorRef);


  onRadioChange(event: MatRadioChange) {
    if (event.value === 'custom') {
      this.openDialog();
    }
  }

  openDialog() {
    const dialogRef = this.dialog.open(CustomFeaturesDialogComponent, {
      width: '600px',
      height: '80vh',
      disableClose: true,
      hasBackdrop: true,
      backdropClass: 'blur-backdrop',
      data: { selectedFeatures: this.customFeaturesControl().value || [] }
    });

    dialogRef.afterClosed().subscribe(result => {
      if (result) {
        this.customFeaturesControl().setValue(result);
      } else if (!this.customFeaturesControl().value?.length) {
        this.control().setValue('standard');
      }
      this.cdr.markForCheck();
    });
  }
}
