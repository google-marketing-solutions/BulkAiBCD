import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  output,
} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatIconModule} from '@angular/material/icon';
import {MatInputModule} from '@angular/material/input';
import {MatRadioModule} from '@angular/material/radio';
import {MatSelectModule} from '@angular/material/select';
import {CommonModule} from '@angular/common';

@Component({
  selector: 'app-id-input',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './id-input.component.html',
  styleUrl: './id-input.component.scss',
  imports: [
    CommonModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    ReactiveFormsModule,
    MatRadioModule,
    MatSelectModule,
  ],
})
export class IdInputComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  readonly filesAdded = output<File[]>();
  readonly idForm = new FormGroup({
    'idType': new FormControl('customerId', Validators.required),
    'idValue': new FormControl('', Validators.required),
    'campaignName': new FormControl({value: '', disabled: true}),
    'campaignNameMatchType': new FormControl({
      value: 'contains',
      disabled: true,
    }),
    'campaignStatus': new FormControl({value: 'active', disabled: true}),
  });

  fetchAndAdd() {
    const ids = (this.idForm.get('idValue')?.value ?? '')
      .split(/[,\s]+/)
      .map((v) => v.trim())
      .filter(Boolean);
    if (ids.length === 0) return;
    const idType = this.idForm.get('idType')?.value ?? 'customerId';
    const files = ids.map(
      (id) => new File([id], `${idType}:${id}`, {type: 'ads/id'}),
    );
    this.filesAdded.emit(files);
    this.idForm.get('idValue')?.reset('', {emitEvent: false});
    this.idForm.get('idValue')?.markAsPristine();
    this.idForm.get('idValue')?.markAsUntouched();
  }

  ngOnInit() {
    this.idForm
      .get('idType')!
      .valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((idType) => {
        const campaignNameControl = this.idForm.get('campaignName');
        const campaignNameMatchTypeControl = this.idForm.get(
          'campaignNameMatchType',
        );
        const campaignStatusControl = this.idForm.get('campaignStatus');

        if (idType === 'campaignId') {
          campaignNameControl?.enable();
          campaignNameMatchTypeControl?.enable();
          campaignStatusControl?.enable();
        } else {
          campaignNameControl?.disable();
          campaignNameMatchTypeControl?.disable();
          campaignStatusControl?.disable();
        }
      });
  }

  get idType() {
    return this.idForm.get('idType');
  }

  get idValue() {
    return this.idForm.get('idValue');
  }

  get placeholderText(): string {
    return this.idType?.value === 'customerId'
      ? 'Customer IDs'
      : 'Campaign IDs';
  }
}
