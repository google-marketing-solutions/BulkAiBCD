import {
  ChangeDetectionStrategy,
  Component,
  input,
  output,
  ViewEncapsulation,
} from '@angular/core';
import {MatIconModule} from '@angular/material/icon';
import {MatTabsModule} from '@angular/material/tabs';

import {DriveInputComponent} from './drive-input/drive-input.component';
import {FileUploadComponent} from './file-upload/file-upload.component';
import {IdInputComponent} from './id-input/id-input.component';
import {UrlInputComponent} from './url-input/url-input.component';

@Component({
  selector: 'app-video-input-tabs',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './video-input-tabs.component.html',
  styleUrl: './video-input-tabs.component.scss',
  encapsulation: ViewEncapsulation.None,
  imports: [
    MatTabsModule,
    FileUploadComponent,
    IdInputComponent,
    UrlInputComponent,
    DriveInputComponent,
    MatIconModule,
  ],
})
export class VideoInputTabsComponent {
  readonly filesAdded = output<File[]>();
  readonly currentCount = input(0);
  readonly videoSourceType = input<'file' | 'id' | 'url' | 'drive' | null>(
    null,
  );
}
