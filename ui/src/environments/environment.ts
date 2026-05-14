import firebaseConfig from './firebase-config.json';

export const environment = {
  production: false,
  apiUrl: '/api/v2',
  firebase: firebaseConfig as {
    apiKey: string;
    authDomain: string;
    projectId: string;
    appId: string;
  },
};
