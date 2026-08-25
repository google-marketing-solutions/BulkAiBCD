import {ComponentFixture, TestBed} from '@angular/core/testing';
import {MatSnackBar} from '@angular/material/snack-bar';
import {NoopAnimationsModule} from '@angular/platform-browser/animations';
import {ActivatedRoute, provideRouter} from '@angular/router';
import {of, throwError} from 'rxjs';

import {GoogleAdsService} from '../../../../services/google-ads.service';
import {IdInputComponent} from './id-input.component';

describe('IdInputComponent', () => {
  let fixture: ComponentFixture<IdInputComponent>;
  let component: IdInputComponent;
  let googleAdsService: jasmine.SpyObj<GoogleAdsService>;
  let snackBar: MatSnackBar;

  beforeEach(async () => {
    googleAdsService = jasmine.createSpyObj<GoogleAdsService>('GoogleAdsService', [
      'getStatus',
      'listCustomers',
      'listCampaigns',
      'listVideoAssets',
      'configure',
    ]);

    googleAdsService.getStatus.and.returnValue(
      of({configured: true, authorized: true}),
    );
    googleAdsService.listCustomers.and.returnValue(of(['1234567890']));
    googleAdsService.listCampaigns.and.returnValue(
      of([{id: 'c1', name: 'Campaign 1', status: 'ENABLED'}]),
    );
    googleAdsService.listVideoAssets.and.returnValue(
      of([{id: 'v1', name: 'Video 1', youtubeVideoId: 'dQw4w9WgXcQ'}]),
    );

    await TestBed.configureTestingModule({
      imports: [IdInputComponent, NoopAnimationsModule],
      providers: [
        provideRouter([]),
        {provide: GoogleAdsService, useValue: googleAdsService},
        {
          provide: ActivatedRoute,
          useValue: {queryParams: of({adsConfigured: 'true'})},
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(IdInputComponent);
    component = fixture.componentInstance;
    snackBar = fixture.debugElement.injector.get(MatSnackBar);
    fixture.detectChanges();
  });

  it('checks status and loads customers on init when authorized', () => {
    expect(googleAdsService.getStatus).toHaveBeenCalled();
    expect(component.isConfigured()).toBeTrue();
    expect(component.isAuthorized()).toBeTrue();
    expect(component.customers()).toEqual(['1234567890']);
    expect(component.searchForm.get('customerId')?.value).toBe('1234567890');
  });

  it('searchCampaigns queries campaigns and populates results signal', () => {
    component.searchForm.patchValue({
      customerId: '1234567890',
      campaignName: 'Test',
    });

    component.searchCampaigns();

    expect(googleAdsService.listCampaigns).toHaveBeenCalledWith(
      '1234567890',
      'Test',
      'contains',
      'active',
    );
    expect(component.campaigns().length).toBe(1);
  });

  it('toggleCampaignSelection and toggleAllCampaigns manage selected IDs', () => {
    component.campaigns.set([
      {id: 'c1', name: 'Camp 1', status: 'ENABLED'},
      {id: 'c2', name: 'Camp 2', status: 'ENABLED'},
    ]);

    component.toggleCampaignSelection('c1');
    expect(component.selectedCampaignIds()).toEqual(['c1']);

    component.toggleCampaignSelection('c1');
    expect(component.selectedCampaignIds()).toEqual([]);

    component.toggleAllCampaigns();
    expect(component.selectedCampaignIds()).toEqual(['c1', 'c2']);

    component.toggleAllCampaigns();
    expect(component.selectedCampaignIds()).toEqual([]);
  });

  it('fetchAndAddVideos downloads video assets and emits YouTube files', () => {
    const emitSpy = jasmine.createSpy();
    component.filesAdded.subscribe(emitSpy);

    component.searchForm.patchValue({customerId: '1234567890'});
    component.selectedCampaignIds.set(['c1']);

    component.fetchAndAddVideos();

    expect(googleAdsService.listVideoAssets).toHaveBeenCalledWith('1234567890', 'c1');
    expect(emitSpy).toHaveBeenCalled();
    const emitted: File[] = emitSpy.calls.mostRecent().args[0];
    expect(emitted.length).toBe(1);
    expect(emitted[0].type).toBe('youtube/url');
  });
});

