# Bulk AiBCD

Score ad-creative videos against the ABCD framework using Vertex AI Gemini. Bulk-ingest
YouTube URLs, Drive folders, or direct file uploads; export results as per-video Google
Slides pitch decks and a Google Sheet report — all owned by the signed-in user in their
own Drive.

## One-click deploy

[![Open in Cloud Shell](https://gstatic.com/cloudssh/images/open-btn.svg)](https://shell.cloud.google.com/cloudshell/editor?cloudshell_git_repo=https://github.com/google-marketing-solutions/BulkAiBCD&cloudshell_workspace=.)

Clicking the button opens Cloud Shell with this repo cloned and gcloud pre-authenticated.
Then run:

```bash
./install.sh YOUR_GCP_PROJECT_ID
```

The installer provisions APIs, Firestore, IAM, Cloud Tasks, GCS, IAP, Firebase, and the
Google sign-in provider via Terraform; builds the container via Cloud Build; and prints
the public Cloud Run URL.

## What you get

- **Cloud Run service** with IAM-gated access. Users reach it via
  `gcloud run services proxy bulkaibcd --region=us-central1` → `http://localhost:8080`.
- **Per-user Drive integration** — Pitch Deck and Detailed Spreadsheet buttons generate
  files owned by the logged-in user, in their own Drive, via Firebase Auth's Google
  provider and the `drive.file` scope.
- **Per-video Google Slides decks** rendered from
  `src/main/resources/templates/master_pitch_deck.pptx` via `batchUpdate` token
  replacement — one deck per selected video, all opened in new tabs.
- **Detailed per-video Google Sheet** with ABCD scores, status, and error highlighting.

## Access control

### Today (IAM-gated)

The Cloud Run service is deployed with `--no-allow-unauthenticated`. Access requires
`roles/run.invoker`. To use the app:

```bash
gcloud run services proxy bulkaibcd --region=us-central1 --project=YOUR_GCP_PROJECT_ID
# then open http://localhost:8080 in a browser
```

To grant a teammate the same access, they need both Viewer and Invoker roles:

```bash
# 1. Grant Viewer role so they can run the proxy command
gcloud run services add-iam-policy-binding bulkaibcd \
  --region=us-central1 --project=YOUR_GCP_PROJECT_ID \
  --member=user:teammate@example.com \
  --role=roles/run.viewer

# 2. Grant Invoker role so they can access/invoke the app
gcloud run services add-iam-policy-binding bulkaibcd \
  --region=us-central1 --project=YOUR_GCP_PROJECT_ID \
  --member=user:teammate@example.com \
  --role=roles/run.invoker
```


## Redeploying

After code changes, re-run `./install.sh YOUR_GCP_PROJECT_ID` — it's idempotent. Terraform
will no-op existing resources; Cloud Build rebuilds the container and rolls out a new
Cloud Run revision.

## Uninstalling

If you want to tear down the deployment and clean up the resources created on your Google Cloud project, run the uninstaller script:

```bash
./uninstall.sh YOUR_GCP_PROJECT_ID
```

This will automatically empty your GCS uploads bucket, delete the Cloud Run service, and run a Terraform destroy to remove all managed infrastructure (like Firestore, Artifact Registry, Cloud Tasks queues, etc.) while safely leaving core APIs enabled so as not to break other applications in your project.

## Local Development

To run the application locally for development:

1. **Backend (Java/Spring Boot)**:
   The application uses Maven. You can run the backend server locally using:
   ```bash
   mvn spring-boot:run
   ```
   *Note: Ensure you have authenticated locally with `gcloud auth application-default login` so the backend can interact with your GCP project resources (Firestore, Cloud Tasks, etc.).*

2. **Frontend (Angular)**:
   The UI is an Angular project located in the `ui/` folder. Please refer to the [ui/README.md](ui/README.md) for detailed instructions on how to start the frontend development server.
