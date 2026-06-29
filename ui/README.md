# Bulk AiBCD - Frontend UI

This project was generated with [Angular CLI](https://github.com/angular/angular-cli).

## Prerequisites

- Node.js (v18+)
- npm

## Development server

1. Install dependencies:
   ```bash
   npm install
   ```

2. Run the development server:
   ```bash
   npm start
   ```

Navigate to `http://localhost:4200/`. The application will automatically reload if you change any of the source files.

## Firebase Configuration

During deployment, the `install.sh` script automatically generates `src/environments/firebase-config.json` via Terraform. For local development, ensure you have run the installer at least once so this file exists, or manually populate it with your Firebase Web App credentials.

## Build

Run `npm run build` to build the project. The build artifacts will be stored in the `dist/` directory.

## Running unit tests

Run `npm test` to execute the unit tests via [Karma](https://karma-runner.github.io).

## Running end-to-end tests

Run `npm run e2e` to execute the end-to-end tests via [Cypress](https://www.cypress.io/).
