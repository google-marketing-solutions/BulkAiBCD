/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
