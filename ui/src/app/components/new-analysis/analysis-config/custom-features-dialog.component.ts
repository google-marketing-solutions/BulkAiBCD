import {CommonModule} from '@angular/common';
import {Component, inject} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatCheckboxModule} from '@angular/material/checkbox';
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from '@angular/material/dialog';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatInputModule} from '@angular/material/input';


const CUSTOM_FEATURES = [
 '(A) Large Supers',
  '(A) Bright Visuals',
  '(A) High Contrast Visuals',
  '(A) Starting Strong',
  '(A) Quick Pacing',
  '(A) Quick Pacing (First 5s)',
  '(A) Tight Framing',
  '(A) Tight Framing (First 5s)',
  '(A) Sound On',
  '(A) Music',
  '(A) Sound Effects',
  '(A) Voice',
  '(A) Voice-Over',
  '(A) Dialogue (1-Person)',
  '(A) Dialogue (2+ People)',
  '(A) Direct to Camera',
  '(A) Supers',
  '(A) Supers with Audio',
  '(A) Supers with Audio (Augmented)',
  '(A) Supers with Audio (See & Say)',
  '(B) Brand Visual',
  '(B) Brand Visual (First 5s)',
  '(B) Brand Visual (Last 5s)',
  '(B) Brand Visual (Overlaid)',
  '(B) Brand Visual (In-situation)',
  '(B) Brand Visual (3+ Times)',
  '(B) Brand Logo (Large)',
  '(B) Brand Mention (Speech)',
  '(B) Brand Mention (Speech) (See & Say)',
  '(B) Brand Mention (Speech) (Last 5s)',
  '(B) Brand Mention (Speech) (First 5s)',
  '(B) Brand Mention (Speech) (See & Say) (First 5s)',
  '(B) Brand Mnemonic',
  '(B) Multiple Brand Elements',
  '(B) Product Visual',
  '(B) Product Visual (First 5s)',
  '(B) Product Visual (Last 5s)',
  '(B) Product Visual (Close-up)',
  '(B) Product Visual (Extreme Close-up)',
  '(B) Product Mention (Speech or Text)',
  '(B) Product Mention (Speech)',
  '(B) Product Mention (Speech) (First 5s)',
  '(B) Product Mention (Speech) (Last 5s)',
  '(B) Product Mention (Text)',
  '(B) Product Focus',
  '(C) Presence of People',
  '(C) Presence of People (First 5s)',
  '(C) Presence of People (Close-up)',
  '(C) Visible Face (First 5s)',
  '(C) Product Interaction',
  '(C) Product Context',
  '(C) Clear Messaging',
  '(C) Single Message',
  '(C) Casual Language',
  '(C) Expression of Benefit',
  '(C) Competitive Claim',
  '(C) Visualization of Benefit',
  '(C) Emotions',
  '(C) Humor',
  '(C) Delight',
  '(C) Character-Driven',
  '(D) Call-to-Action (Text)',
  '(D) Power of Free (Speech)',
  '(D) Path to Purchase',
  '(D) Search Bar',
  '(D) Call-to-Action (Speech)',
  '(D) Relevant Call-to-Action',
  '(D) Purchase Incentive (Limited Time/Quantities)',
  '(D) Special Offer (Text)',
  '(D) Special Offer (Speech)',
  '(D) Price (Text)',
  '(D) Price (Speech)',
  '(D) Power of Free (Text)',
];

/**
 * Data passed into the CustomFeaturesDialogComponent.
 * Contains the initial array of currently selected features to populate the dialog.
 */
export interface DialogData {
  selectedFeatures: string[];
}

/**
 * Component for a dialog that allows users to select custom features from a predefined list.
 * It includes a search bar to filter the features and displays the count of selected features.
 * The selected features are returned as an array of strings when the dialog is closed with "Save".
 */
@Component({
  selector: 'app-custom-features-dialog',
  template: `
    <h2 mat-dialog-title>Select Custom Features</h2>

    <mat-dialog-content>
      <!-- Search Bar -->
      <mat-form-field appearance="outline" class="search-bar">
        <mat-label>Search features...</mat-label>
        <input matInput [(ngModel)]="searchQuery" placeholder="e.g. Brand Visual">
      </mat-form-field>

      <!-- Checkbox List -->
      <div class="features-list">
        <mat-checkbox *ngFor="let feature of filteredFeatures"
                      [checked]="isSelected(feature)"
                      (change)="toggleFeature(feature, $event.checked)">
          {{ feature }}
        </mat-checkbox>
      </div>

      <!-- Show when search yields no results -->
      <div *ngIf="filteredFeatures.length === 0" class="no-results">
        No features found matching "{{ searchQuery }}"
      </div>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <div class="selection-count">
        {{ selectedFeatures.size }} selected
      </div>
      <button mat-button (click)="onCancel()">Cancel</button>
      <button mat-flat-button color="primary" (click)="onSave()">Save</button>
    </mat-dialog-actions>`,
  styles: [`
    .search-bar {
      width: 100%;
      margin-bottom: 8px;
    }
    .features-list {
      display: flex;
      flex-direction: column;
      gap: 8px;
      max-height: 400px; /* Keeps the dialog from getting too tall */
    }
    mat-dialog-actions {
      justify-content: space-between; /* Puts count on left, buttons on right */
    }
    .selection-count {
      color: #666;
      font-size: 14px;
      margin-left: 16px;
    }
    .no-results {
      color: #888;
      font-style: italic;
      padding: 16px 0;
    }
  `],
  standalone: true,
  imports: [
    MatDialogModule,
    MatCheckboxModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    CommonModule,
    FormsModule
  ]
})
/**
 * Component for a dialog that allows users to select custom features from a predefined list.
 * It includes a search bar to filter the features and displays the count of selected features.
 * Allows users to search and select from a predefined list of features.
 * The selected features are returned when the dialog is closed with "Save".
 */
export class CustomFeaturesDialogComponent {
  dialogRef = inject(MatDialogRef<CustomFeaturesDialogComponent>);
  data = inject<DialogData>(MAT_DIALOG_DATA);

  // Load the static array
  features: string[] = CUSTOM_FEATURES;
  searchQuery = '';

  // Initialize the Set with whatever was passed in, or empty array if none
  selectedFeatures: Set<string> = new Set(this.data?.selectedFeatures || []);

  // Getter to dynamically filter the list based on the search bar
  get filteredFeatures(): string[] {
    if (!this.searchQuery) return this.features;

    const lowerQuery = this.searchQuery.toLowerCase();
    return this.features.filter(feature =>
      feature.toLowerCase().includes(lowerQuery)
    );
  }

  // Check if a string is in the Set
  isSelected(feature: string) {
    return this.selectedFeatures.has(feature);
  }

  // Add or remove the string from the Set
  toggleFeature(feature: string, checked: boolean) {
    if (checked) {
      this.selectedFeatures.add(feature);
    } else {
      this.selectedFeatures.delete(feature);
    }
  }

  onCancel() {
    this.dialogRef.close();
  }

  onSave() {
    this.dialogRef.close(Array.from(this.selectedFeatures));
  }
}