import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatListModule } from '@angular/material/list';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { forkJoin, of } from 'rxjs';
import { catchError, finalize } from 'rxjs/operators';
import { GoogleAdsService, CampaignDto } from '../../../../services/google-ads.service';

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
    MatCheckboxModule,
    MatProgressSpinnerModule,
    MatListModule,
    MatSnackBarModule,
  ],
})
export class IdInputComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly googleAdsService = inject(GoogleAdsService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);

  readonly filesAdded = output<File[]>();

  // Configuration Form
  readonly configForm = new FormGroup({
    clientId: new FormControl('', Validators.required),
    clientSecret: new FormControl('', Validators.required),
    developerToken: new FormControl('', Validators.required),
  });

  // Search Form
  readonly searchForm = new FormGroup({
    customerId: new FormControl('', Validators.required),
    campaignName: new FormControl(''),
    campaignNameMatchType: new FormControl('contains'),
    campaignStatus: new FormControl('active'),
  });

  // UI State Signals
  readonly isConfigured = signal(false);
  readonly isAuthorized = signal(false);
  readonly showConfigForm = signal(false);
  readonly loadingStatus = signal(true);
  readonly loadingCustomers = signal(false);
  readonly loadingCampaigns = signal(false);
  readonly fetchingVideos = signal(false);

  // Data Signals
  readonly customers = signal<string[]>([]);
  readonly campaigns = signal<CampaignDto[]>([]);
  readonly selectedCampaignIds = signal<string[]>([]);

  ngOnInit() {
    this.checkStatus();
    this.handleRedirectParams();
  }

  private checkStatus() {
    this.loadingStatus.set(true);
    this.googleAdsService.getStatus()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loadingStatus.set(false))
      )
      .subscribe({
        next: (status) => {
          this.isConfigured.set(status.configured);
          this.isAuthorized.set(status.authorized);
          this.showConfigForm.set(!status.configured || !status.authorized);
          if (status.configured && status.authorized) {
            this.loadCustomers();
          }
        },
        error: (err) => {
          logError('Failed to check Google Ads status', err);
        }
      });
  }

  private handleRedirectParams() {
    this.route.queryParams
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(params => {
        if (params['adsConfigured'] === 'true') {
          this.snackBar.open('Google Ads authorized successfully!', 'Dismiss', { duration: 5000 });
          this.clearQueryParams();
          this.checkStatus();
        } else if (params['adsError']) {
          this.snackBar.open(`Authorization failed: ${params['adsError']}`, 'Dismiss', { duration: 8000 });
          this.clearQueryParams();
        }
      });
  }

  private clearQueryParams() {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { adsConfigured: null, adsError: null },
      queryParamsHandling: 'merge',
    });
  }

  private loadCustomers() {
    this.loadingCustomers.set(true);
    this.googleAdsService.listCustomers()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loadingCustomers.set(false))
      )
      .subscribe({
        next: (custs) => {
          this.customers.set(custs);
          if (custs.length > 0) {
            this.searchForm.patchValue({ customerId: custs[0] });
          }
        },
        error: (err) => {
          this.snackBar.open('Could not load accessible customers. You can type your Customer ID manually.', 'Dismiss', { duration: 5000 });
        }
      });
  }

  saveConfigAndAuthorize() {
    if (this.configForm.invalid) return;

    const val = this.configForm.value;
    const frontendOrigin = window.location.origin;

    this.snackBar.open('Initiating authorization...', 'Dismiss', { duration: 2000 });

    this.googleAdsService.configure(
      val.clientId!,
      val.clientSecret!,
      val.developerToken!,
      frontendOrigin
    )
    .pipe(takeUntilDestroyed(this.destroyRef))
    .subscribe({
      next: (res) => {
        // Redirect browser to Google Consent Screen
        window.location.href = res.authorizationUrl;
      },
      error: (err) => {
        this.snackBar.open(`Configuration failed: ${err?.error?.error || 'unknown error'}`, 'Dismiss', { duration: 6000 });
      }
    });
  }

  searchCampaigns() {
    if (this.searchForm.invalid) return;

    const val = this.searchForm.value;
    this.loadingCampaigns.set(true);
    this.campaigns.set([]);
    this.selectedCampaignIds.set([]);

    this.googleAdsService.listCampaigns(
      val.customerId!,
      val.campaignName || undefined,
      val.campaignNameMatchType || undefined,
      val.campaignStatus || undefined
    )
    .pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.loadingCampaigns.set(false))
    )
    .subscribe({
      next: (camps) => {
        this.campaigns.set(camps);
        if (camps.length === 0) {
          this.snackBar.open('No campaigns found matching your search criteria.', 'Dismiss', { duration: 5000 });
        }
      },
      error: (err) => {
        this.snackBar.open(`Failed to load campaigns: ${err?.message || 'unknown error'}`, 'Dismiss', { duration: 6000 });
      }
    });
  }

  toggleCampaignSelection(campaignId: string) {
    this.selectedCampaignIds.update(ids => {
      if (ids.includes(campaignId)) {
        return ids.filter(id => id !== campaignId);
      } else {
        return [...ids, campaignId];
      }
    });
  }

  toggleAllCampaigns() {
    if (this.selectedCampaignIds().length === this.campaigns().length) {
      this.selectedCampaignIds.set([]);
    } else {
      this.selectedCampaignIds.set(this.campaigns().map(c => c.id));
    }
  }

  fetchAndAddVideos() {
    const customerId = this.searchForm.get('customerId')?.value;
    const campaignIds = this.selectedCampaignIds();

    if (!customerId || campaignIds.length === 0) return;

    this.fetchingVideos.set(true);
    this.snackBar.open(`Fetching video assets from ${campaignIds.length} campaign(s)...`, 'Dismiss');

    const requests = campaignIds.map(campaignId =>
      this.googleAdsService.listVideoAssets(customerId, campaignId).pipe(
        catchError(err => {
          console.error(`Failed to fetch videos for campaign ${campaignId}`, err);
          return of([]); // Fallback to empty list so other campaigns still succeed
        })
      )
    );

    forkJoin(requests)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.fetchingVideos.set(false))
      )
      .subscribe({
        next: (results) => {
          const allVideoAssets = results.flat();
          if (allVideoAssets.length === 0) {
            this.snackBar.open('No YouTube video assets found in the selected campaigns.', 'Dismiss', { duration: 5000 });
            return;
          }

          // Create standard YouTube source Files
          const files = allVideoAssets.map(asset => {
            const url = `https://www.youtube.com/watch?v=${asset.youtubeVideoId}`;
            const file = new File([url], asset.name || url, { type: 'youtube/url' });
            (file as any).sourceUrl = url;
            return file;
          });

          this.filesAdded.emit(files);
          this.snackBar.open(`Added ${files.length} YouTube videos to the queue!`, 'Dismiss', { duration: 5000 });

          // Reset search selections
          this.selectedCampaignIds.set([]);
          this.campaigns.set([]);
          this.searchForm.get('campaignName')?.reset();
        },
        error: (err) => {
          this.snackBar.open('An error occurred while fetching video assets.', 'Dismiss', { duration: 6000 });
        }
      });
  }

  toggleConfigForm() {
    this.showConfigForm.update(val => !val);
  }
}

function logError(msg: string, err: any) {
  console.error(msg, err);
}
