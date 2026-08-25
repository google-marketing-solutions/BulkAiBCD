import {TestBed} from '@angular/core/testing';

import {AuthFailure, GoogleAuthService} from './google-auth.service';

describe('GoogleAuthService', () => {
  let service: GoogleAuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(GoogleAuthService);
  });

  it('starts with null email and cached token', () => {
    expect(service.currentUserEmail()).toBeNull();
  });

  it('clearToken resets cached token, expiresAt, and email', () => {
    (service as any).cachedToken = 'some-token';
    (service as any).cachedExpiresAt = Date.now() + 100000;
    (service as any).cachedEmail = 'user@example.com';

    service.clearToken();

    expect(service.currentUserEmail()).toBeNull();
    expect((service as any).cachedToken).toBeNull();
    expect((service as any).cachedExpiresAt).toBe(0);
  });

  it('returns cached token when still valid and not near expiry', async () => {
    (service as any).cachedToken = 'cached-token-123';
    (service as any).cachedExpiresAt = Date.now() + 20 * 60 * 1000; // 20 mins from now

    const token = await service.requestDriveToken();
    expect(token).toBe('cached-token-123');
  });

  it('AuthFailure models different failure reasons properly', () => {
    const userDismissed = new AuthFailure('USER_DISMISSED', 'User closed popup');
    expect(userDismissed.reason).toBe('USER_DISMISSED');
    expect(userDismissed.message).toBe('User closed popup');

    const popupBlocked = new AuthFailure('POPUP_BLOCKED', 'Popup blocked');
    expect(popupBlocked.reason).toBe('POPUP_BLOCKED');

    const domainError = new AuthFailure('UNAUTHORIZED_DOMAIN', 'Unauthorized domain');
    expect(domainError.reason).toBe('UNAUTHORIZED_DOMAIN');
  });
});

