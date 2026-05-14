import {ChangeDetectionStrategy, Component} from '@angular/core';
import {JobsTableComponent} from './jobs-table/jobs-table.component';

@Component({
  selector: 'app-list-analyses',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './list-analyses.component.html',
  styleUrl: './list-analyses.component.scss',
  imports: [JobsTableComponent],
})
export class ListAnalysesComponent {}
