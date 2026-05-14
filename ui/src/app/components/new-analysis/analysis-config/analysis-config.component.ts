import {ChangeDetectionStrategy, Component, input} from '@angular/core';
import {FormControl, ReactiveFormsModule} from '@angular/forms';
import {MatIconModule} from '@angular/material/icon';
import {MatRadioModule} from '@angular/material/radio';
import {MatTooltipModule} from '@angular/material/tooltip';

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
  ],
})
export class AnalysisConfigComponent {
  // Supplied by the parent so the submit handler can read the current value.
  // Falls back to a local control if the parent ever renders this without one.
  readonly control = input<FormControl<string | null>>(
    new FormControl<string | null>('standard'),
  );
}
