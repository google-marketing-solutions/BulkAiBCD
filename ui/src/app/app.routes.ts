import { Routes } from '@angular/router';
import { NewAnalysisComponent } from './components/new-analysis/new-analysis.component';
import { ListAnalysesComponent } from './components/list-analyses/list-analyses.component';
import { ResultsComponent } from './components/results/results.component';

export const routes: Routes = [
  { path: '', component: NewAnalysisComponent, pathMatch: 'full' },
  { path: 'list', component: ListAnalysesComponent },
  { path: 'list/results/:analysisId', component: ResultsComponent },
  { path: '**', redirectTo: '' },
];
