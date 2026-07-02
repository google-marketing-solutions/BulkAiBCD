import {ChangeDetectionStrategy, ChangeDetectorRef, Component, inject, input} from '@angular/core';
import {FormControl, ReactiveFormsModule} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatDialog, MatDialogModule} from '@angular/material/dialog';
import {MatIconModule} from '@angular/material/icon';
import {MatRadioChange, MatRadioModule} from '@angular/material/radio';
import {MatTooltipModule} from '@angular/material/tooltip';
import {CustomFeaturesDialogComponent} from './custom-features-dialog/custom-features-dialog.component';

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

  readonly customFeaturesControl = input<FormControl<{long: string[], short: string[]} | null>>(
    new FormControl<{long: string[], short: string[]}>({long: [], short: []})
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
      data: { 
        selectedFeaturesLong: this.customFeaturesControl().value?.long || [],
        selectedFeaturesShort: this.customFeaturesControl().value?.short || []
      }
    });

    dialogRef.afterClosed().subscribe(result => {
      if (result) {
        this.customFeaturesControl().setValue(result);
      } else if (!this.customFeaturesControl().value?.long.length && !this.customFeaturesControl().value?.short.length) {
        this.control().setValue('standard');
      }
      this.cdr.markForCheck();
    });
  }
}
