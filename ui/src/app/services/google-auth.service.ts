import {Injectable} from '@angular/core';
import {FirebaseApp, initializeApp} from 'firebase/app';
import {
  Auth,
  browserLocalPersistence,
  getAuth,
  GoogleAuthProvider,
  setPersistence,
  signInWithPopup,
  signOut,
} from 'firebase/auth';

import {environment} from '../../environments/environment';

/**
 * Browser-side Google OAuth flow that returns a `drive.file`-scoped access
 * token the backend can use to create files in the signed-in user's Drive.
 *
 * <p>Uses popup mode ({@link signInWithPopup}) so the consent UI opens in a
 * separate window and the caller's tab stays put. Requires the app's origin
 * (e.g. {@code localhost}) to be listed in Firebase Auth's
 * {@code authorizedDomains}; initialize or {@code install.sh} handles that.
 */
const DRIVE_FILE_SCOPE = 'https://www.googleapis.com/auth/drive.file';
const EXPIRY_BUFFER_MS = 5 * 60 * 1000;

export type AuthFailureReason =
  | 'USER_DISMISSED'
  | 'POPUP_BLOCKED'
  | 'UNAUTHORIZED_DOMAIN'
  | 'UNKNOWN';

export class AuthFailure extends Error {
  constructor(public readonly reason: AuthFailureReason, message: string) {
    super(message);
  }
}

/** Kept exported for backwards compat with existing callers that import it. */
export interface PendingDriveAction {
  kind: 'pitchDeck' | 'spreadsheet';
  analysisId: string;
  videoIds?: string[];
}

@Injectable({providedIn: 'root'})
export class GoogleAuthService {
  private app: FirebaseApp | null = null;
  private auth: Auth | null = null;
  private cachedToken: string | null = null;
  private cachedExpiresAt = 0;
  private cachedEmail: string | null = null;

  /**
   * Returns a valid drive.file access token, opening the Google consent popup
   * when needed. Must be called from a user gesture (button click) — browsers
   * block window.open() otherwise.
   */
  async requestDriveToken(): Promise<string> {
    if (this.cachedToken && Date.now() < this.cachedExpiresAt - EXPIRY_BUFFER_MS) {
      return this.cachedToken;
    }
    const auth = this.ensureAuth();
    await setPersistence(auth, browserLocalPersistence);
    const provider = new GoogleAuthProvider();
    provider.addScope(DRIVE_FILE_SCOPE);
    try {
      const result = await signInWithPopup(auth, provider);
      const credential = GoogleAuthProvider.credentialFromResult(result);
      const accessToken = credential?.accessToken;
      if (!accessToken) {
        throw new AuthFailure('UNKNOWN', 'Google returned no access token.');
      }
      this.cachedToken = accessToken;
      // Google access tokens last ~1h; Firebase doesn't surface exact expiry.
      this.cachedExpiresAt = Date.now() + 55 * 60 * 1000;
      this.cachedEmail = result.user?.email ?? null;
      return accessToken;
    } catch (err) {
      this.clearToken();
      throw translateFirebaseError(err);
    }
  }

  currentUserEmail(): string | null {
    return this.cachedEmail;
  }

  clearToken(): void {
    this.cachedToken = null;
    this.cachedExpiresAt = 0;
    this.cachedEmail = null;
    if (this.auth) {
      signOut(this.auth).catch(() => void 0);
    }
  }

  private ensureAuth(): Auth {
    if (!this.auth) {
      if (!environment.firebase?.apiKey) {
        throw new AuthFailure(
          'UNKNOWN',
          'Firebase config missing. Did install.sh overwrite firebase-config.json?',
        );
      }
      this.app = initializeApp(environment.firebase);
      this.auth = getAuth(this.app);
    }
    return this.auth;
  }
}

function translateFirebaseError(err: unknown): AuthFailure {
  if (err instanceof AuthFailure) return err;
  const code = (err as {code?: string} | undefined)?.code ?? '';
  if (code === 'auth/popup-closed-by-user' || code === 'auth/cancelled-popup-request') {
    return new AuthFailure('USER_DISMISSED', 'Sign-in cancelled.');
  }
  if (code === 'auth/popup-blocked') {
    return new AuthFailure('POPUP_BLOCKED', 'Browser blocked the sign-in popup.');
  }
  if (code === 'auth/unauthorized-domain') {
    return new AuthFailure(
      'UNAUTHORIZED_DOMAIN',
      'Firebase Auth is not authorised for this origin. Add it to authorizedDomains.',
    );
  }
  const message = (err as {message?: string} | undefined)?.message ?? 'Sign-in failed';
  return new AuthFailure('UNKNOWN', message);
}
