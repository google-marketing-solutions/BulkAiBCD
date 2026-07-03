import {CommonModule} from '@angular/common';
import {Component, inject} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {MatButtonModule} from '@angular/material/button';
import {MatCheckboxModule} from '@angular/material/checkbox';
import {MAT_DIALOG_DATA, MatDialogModule, MatDialogRef} from '@angular/material/dialog';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatInputModule} from '@angular/material/input';


import {MatTabsModule} from '@angular/material/tabs';

const LONG_FEATURES = [
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
  '(D) Power of Free (Text)'
];

const SHORTS_FEATURES = [
  '(A) Tight Framing',
  '(A) Voice',
  '(A) Direct to Camera',
  '(A) Supers',
  '(B) Product Visual (Close-up)',
  '(B) Product Visual (Extreme Close-up)',
  '(C) Product Interaction',
  '(C) Casual Language',
  '(C) Humor',
  '(C) Character-Driven',
  '(D) Call-to-Action (Speech)',
  '(D) Special Offer (Speech)',
  '(A) Shorts Production Style (User Generated)',
  '(A) Short Form Video Adaptation_high',
  '(A) Vertical Format',
  '(B) Brand Secondary Element',
  '(B) Secondary Product Context',
  '(C) Emoji Usage',
  '(C) Direct to Camera Character Talk',
  '(C) Uses Influencer/Creator'
];

/**
 * Data passed into the CustomFeaturesDialogComponent.
 * Contains the initial array of currently selected features to populate the dialog.
 */
export interface DialogData {
  selectedFeaturesLong: string[];
  selectedFeaturesShort: string[];
}

/**
 * Component for a dialog that allows users to select custom features from a predefined list.
 * Supports a 2x2 grid per format (Long vs Shorts).
 */
@Component({
  selector: 'app-custom-features-dialog',
  templateUrl: './custom-features-dialog.component.html',
  styleUrl: './custom-features-dialog.component.scss',
  standalone: true,
  imports: [
    MatDialogModule,
    MatCheckboxModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatTabsModule,
    CommonModule,
    FormsModule
  ]
})
export class CustomFeaturesDialogComponent {
  dialogRef = inject(MatDialogRef<CustomFeaturesDialogComponent>);
  data = inject<DialogData>(MAT_DIALOG_DATA);

  searchQuery = '';

  longGroups = this.groupFeatures(LONG_FEATURES);
  shortsGroups = this.groupFeatures(SHORTS_FEATURES);

  // Initialize the Sets with whatever was passed in
  selectedFeaturesLong: Set<string> = new Set(this.data?.selectedFeaturesLong || []);
  selectedFeaturesShort: Set<string> = new Set(this.data?.selectedFeaturesShort || []);

  groupFeatures(features: string[]) {
    const groups: { [key: string]: string[] } = { A: [], B: [], C: [], D: [] };
    features.forEach(f => {
      if (f.startsWith('(A)')) groups['A'].push(f);
      else if (f.startsWith('(B)')) groups['B'].push(f);
      else if (f.startsWith('(C)')) groups['C'].push(f);
      else if (f.startsWith('(D)')) groups['D'].push(f);
    });
    return groups;
  }

  getFiltered(items: string[]): string[] {
    if (!this.searchQuery) return items;
    const lowerQuery = this.searchQuery.toLowerCase();
    return items.filter(feature => feature.toLowerCase().includes(lowerQuery));
  }

  isSelectedLong(feature: string) {
    return this.selectedFeaturesLong.has(feature);
  }

  toggleFeatureLong(feature: string, checked: boolean) {
    if (checked) {
      this.selectedFeaturesLong.add(feature);
    } else {
      this.selectedFeaturesLong.delete(feature);
    }
  }

  isSelectedShort(feature: string) {
    return this.selectedFeaturesShort.has(feature);
  }

  toggleFeatureShort(feature: string, checked: boolean) {
    if (checked) {
      this.selectedFeaturesShort.add(feature);
    } else {
      this.selectedFeaturesShort.delete(feature);
    }
  }

  onTabChange(event: any) {
    // Optionally reset search query on tab change
    // this.searchQuery = '';
  }

  isAllLongSelected(): boolean {
    return LONG_FEATURES.length > 0 && LONG_FEATURES.every(f => this.selectedFeaturesLong.has(f));
  }

  toggleAllLong(checked: boolean) {
    if (checked) {
      LONG_FEATURES.forEach(f => this.selectedFeaturesLong.add(f));
    } else {
      LONG_FEATURES.forEach(f => this.selectedFeaturesLong.delete(f));
    }
  }

  isAllShortsSelected(): boolean {
    return SHORTS_FEATURES.length > 0 && SHORTS_FEATURES.every(f => this.selectedFeaturesShort.has(f));
  }

  toggleAllShorts(checked: boolean) {
    if (checked) {
      SHORTS_FEATURES.forEach(f => this.selectedFeaturesShort.add(f));
    } else {
      SHORTS_FEATURES.forEach(f => this.selectedFeaturesShort.delete(f));
    }
  }

  onCancel() {
    this.dialogRef.close();
  }

  onSave() {
    this.dialogRef.close({
      long: Array.from(this.selectedFeaturesLong),
      short: Array.from(this.selectedFeaturesShort)
    });
  }
}