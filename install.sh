#!/usr/bin/env bash
#
# Bulk AiBCD one-command installer.
#
# Designed to run inside Cloud Shell (gcloud + terraform pre-installed, user
# already auth'd). Provisions everything — APIs, Firestore, SA, Cloud Tasks,
# GCS, IAP brand/client, Firebase project + Google sign-in provider, Cloud Run
# service — then builds the container and deploys.
#
# Usage:
#   ./install.sh <PROJECT_ID> [--region us-central1]
#
set -euo pipefail

usage() {
  cat <<EOF >&2
Usage: $0 <PROJECT_ID> [--region REGION]

  <PROJECT_ID>    GCP project that will host the deployment.
  --region        Cloud Run / Firestore region. Defaults to us-central1.
EOF
  exit 1
}

PROJECT=""
REGION="us-central1"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      REGION="$2"
      shift 2
      ;;
    --region=*)
      REGION="${1#*=}"
      shift
      ;;
    -h|--help)
      usage
      ;;
    -*)
      echo "Unknown flag: $1" >&2
      usage
      ;;
    *)
      if [[ -n "${PROJECT}" ]]; then
        echo "Multiple project IDs supplied ('${PROJECT}' and '$1')" >&2
        usage
      fi
      PROJECT="$1"
      shift
      ;;
  esac
done
[[ -n "${PROJECT}" ]] || usage

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

banner() { printf '\n\033[1;34m== %s ==\033[0m\n' "$*"; }
info()   { printf '   %s\n' "$*"; }
warn()   { printf '\033[1;33m   %s\033[0m\n' "$*" >&2; }
fail()   { printf '\033[1;31m   %s\033[0m\n' "$*" >&2; exit 1; }

tf_out() { terraform -chdir=infra output -raw "$1" 2>/dev/null; }

# -----------------------------------------------------------------------------
banner "Preflight"
# -----------------------------------------------------------------------------

command -v gcloud >/dev/null 2>&1 || fail "gcloud not found. Use Cloud Shell or install the gcloud CLI."
command -v terraform >/dev/null 2>&1 || fail "terraform not found. Use Cloud Shell or install Terraform 1.5+."

ACTIVE_ACCOUNT="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | head -n1)"
[[ -n "${ACTIVE_ACCOUNT}" ]] || fail "No active gcloud auth. Run: gcloud auth login"
info "gcloud authed as: ${ACTIVE_ACCOUNT}"
info "Project:          ${PROJECT}"
info "Region:           ${REGION}"

gcloud projects describe "${PROJECT}" >/dev/null 2>&1 \
  || fail "Project '${PROJECT}' not accessible with '${ACTIVE_ACCOUNT}'."
gcloud config set project "${PROJECT}" >/dev/null

BILLING="$(gcloud billing projects describe "${PROJECT}" --format='value(billingEnabled)' 2>/dev/null || true)"
if [[ "${BILLING}" != "True" ]]; then
  fail "Project '${PROJECT}' has no billing account. Link one in https://console.cloud.google.com/billing and retry."
fi
info "Billing enabled."

# -----------------------------------------------------------------------------
banner "Enabling required APIs"
# -----------------------------------------------------------------------------

gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  cloudtasks.googleapis.com \
  firestore.googleapis.com \
  aiplatform.googleapis.com \
  artifactregistry.googleapis.com \
  storage.googleapis.com \
  iam.googleapis.com \
  cloudresourcemanager.googleapis.com \
  drive.googleapis.com \
  slides.googleapis.com \
  sheets.googleapis.com \
  iap.googleapis.com \
  firebase.googleapis.com \
  identitytoolkit.googleapis.com \
  --project="${PROJECT}" --quiet

info "APIs enabled."

# -----------------------------------------------------------------------------
banner "Terraform — pass 1 (everything except Cloud Run IAM)"
# -----------------------------------------------------------------------------
# Cloud Run IAM bindings + IAP user bindings target a service that doesn't
# exist yet; var.cloud_run_deployed=false makes Terraform skip them on the
# first pass. They get applied in pass 2 after Cloud Build creates the service.

UPLOADS_BUCKET="bulkaibcd-uploads-${PROJECT}"
QUEUE_ID="bulkaibcd-queue"

# Check if Cloud Run service already exists (for redeployments)
EXISTING_URL="$(gcloud run services describe bulkaibcd --region="${REGION}" --project="${PROJECT}" --format='value(status.url)' 2>/dev/null || true)"

if [[ -n "${EXISTING_URL}" ]]; then
  info "Found existing Cloud Run service: ${EXISTING_URL}"
  cat > infra/terraform.tfvars <<EOF
project_id           = "${PROJECT}"
region               = "${REGION}"
support_email        = "${ACTIVE_ACCOUNT}"
iap_users            = ["${ACTIVE_ACCOUNT}"]
uploads_bucket_name  = "${UPLOADS_BUCKET}"
queue_id             = "${QUEUE_ID}"
cloud_run_deployed   = true
cors_origins         = ["${EXISTING_URL}", "http://localhost:4200", "http://localhost:8080"]
EOF
else
  cat > infra/terraform.tfvars <<EOF
project_id           = "${PROJECT}"
region               = "${REGION}"
support_email        = "${ACTIVE_ACCOUNT}"
iap_users            = ["${ACTIVE_ACCOUNT}"]
uploads_bucket_name  = "${UPLOADS_BUCKET}"
queue_id             = "${QUEUE_ID}"
cloud_run_deployed   = false
cors_origins         = ["http://localhost:4200", "http://localhost:8080"]
EOF
fi
info "Wrote infra/terraform.tfvars"

terraform -chdir=infra init -upgrade -input=false
terraform -chdir=infra apply -auto-approve -input=false

RUNTIME_SA="$(tf_out runtime_service_account)"
[[ -n "${RUNTIME_SA}" ]] || fail "Terraform didn't surface runtime_service_account output."
info "Runtime SA: ${RUNTIME_SA}"

# -----------------------------------------------------------------------------
banner "Exporting Firebase config for the UI build"
# -----------------------------------------------------------------------------

FIREBASE_CONFIG_PATH="ui/src/environments/firebase-config.json"
terraform -chdir=infra output -json firebase_web_config > "${FIREBASE_CONFIG_PATH}"
info "Wrote ${FIREBASE_CONFIG_PATH}"

# -----------------------------------------------------------------------------
banner "Sanity-check Identity Platform Google provider"
# -----------------------------------------------------------------------------

TOKEN="$(gcloud auth print-access-token 2>/dev/null)"
PROVIDER_STATUS="$(
  curl -sS -H "Authorization: Bearer ${TOKEN}" \
    "https://identitytoolkit.googleapis.com/admin/v2/projects/${PROJECT}/defaultSupportedIdpConfigs/google.com" \
    | grep -o '"enabled": *true' || true
)"

if [[ -z "${PROVIDER_STATUS}" ]]; then
  warn "Google sign-in provider not enabled yet. Manual fallback:"
  echo
  echo "   1. Open: https://console.firebase.google.com/project/${PROJECT}/authentication/providers"
  echo "   2. Click 'Google' → toggle Enable → Save."
  echo
  echo "   3. Configure the OAuth Consent Screen scopes in GCP Console:"
  echo "      a. Open: https://console.cloud.google.com/auth/scopes?project=${PROJECT}"
  echo "      b. Click 'Add or Remove Scopes'."
  echo "      c. Add/enable the following scopes:"
  echo "         - https://www.googleapis.com/auth/drive"
  echo "         - https://www.googleapis.com/auth/spreadsheets"
  echo "      d. Save the changes."
  echo
  read -r -p "   Press Enter when both steps are done: " _
fi

# -----------------------------------------------------------------------------
banner "Cloud Build — first pass (creates the Cloud Run service)"
# -----------------------------------------------------------------------------
# APP_BACKEND_URL is unknown until Cloud Run mints it, so this pass leaves it
# empty. Cloud Tasks OIDC callbacks will fail on this revision; the second pass
# fixes them.

gcloud builds submit \
  --config=cloudbuild.yaml \
  --project="${PROJECT}" \
  --substitutions="_REGION=${REGION},_RUNTIME_SA=${RUNTIME_SA},_UPLOADS_BUCKET=${UPLOADS_BUCKET},_CLOUD_TASKS_QUEUE=${QUEUE_ID}"

CLOUD_RUN_URL="$(gcloud run services describe bulkaibcd --region="${REGION}" --project="${PROJECT}" --format='value(status.url)' 2>/dev/null || true)"
[[ -n "${CLOUD_RUN_URL}" ]] || fail "Cloud Run service URL not available after first deploy."
info "Cloud Run URL: ${CLOUD_RUN_URL}"

# -----------------------------------------------------------------------------
banner "Terraform — pass 2 (IAP + Cloud Run IAM bindings + CORS for the real URL)"
# -----------------------------------------------------------------------------

# Cleanly rewrite terraform.tfvars with the live Cloud Run URL
cat > infra/terraform.tfvars <<EOF
project_id           = "${PROJECT}"
region               = "${REGION}"
support_email        = "${ACTIVE_ACCOUNT}"
iap_users            = ["${ACTIVE_ACCOUNT}"]
uploads_bucket_name  = "${UPLOADS_BUCKET}"
queue_id             = "${QUEUE_ID}"
cloud_run_deployed   = true
cors_origins         = ["${CLOUD_RUN_URL}", "http://localhost:4200", "http://localhost:8080"]
EOF
info "Updated infra/terraform.tfvars with live Cloud Run URL"

terraform -chdir=infra apply -auto-approve -input=false

# -----------------------------------------------------------------------------
banner "Cloud Build — second pass (wires APP_BACKEND_URL for Cloud Tasks)"
# -----------------------------------------------------------------------------

gcloud builds submit \
  --config=cloudbuild.yaml \
  --project="${PROJECT}" \
  --substitutions="_REGION=${REGION},_RUNTIME_SA=${RUNTIME_SA},_UPLOADS_BUCKET=${UPLOADS_BUCKET},_APP_BACKEND_URL=${CLOUD_RUN_URL},_CLOUD_TASKS_QUEUE=${QUEUE_ID}"

# -----------------------------------------------------------------------------
banner "Done"
# -----------------------------------------------------------------------------

cat <<EOF

Bulk AiBCD is deployed at:

   ${CLOUD_RUN_URL}

By default, the Cloud Run service is IAM-gated (private). You can choose one of the following options to grant access to others:

=============================================================================
OPTION 1: Make the app PUBLIC (Anyone with the link can access it)
=============================================================================
Run this command in your terminal:

   gcloud run services add-iam-policy-binding bulkaibcd \\
     --region=${REGION} \\
     --project=${PROJECT} \\
     --member=allUsers \\
     --role=roles/run.invoker

Once run, anyone can access the app directly at:
   ${CLOUD_RUN_URL}

=============================================================================
OPTION 2: Keep the app PRIVATE & grant access to a specific teammate
=============================================================================
1. Grant your teammate permissions (both Viewer and Invoker are required to run the proxy and access the app):

   # A. Grant Viewer role so they can run the proxy command
   gcloud run services add-iam-policy-binding bulkaibcd \\
     --region=${REGION} \\
     --project=${PROJECT} \\
     --member=user:teammate@example.com \\
     --role=roles/run.viewer

   # B. Grant Invoker role so they can access/invoke the app
   gcloud run services add-iam-policy-binding bulkaibcd \\
     --region=${REGION} \\
     --project=${PROJECT} \\
     --member=user:teammate@example.com \\
     --role=roles/run.invoker

2. Your teammate must then run the proxy command in their own terminal:

   gcloud run services proxy bulkaibcd --region=${REGION} --project=${PROJECT}

3. How they access the app:
   - If using Google Cloud Shell: Click the "Web Preview" button (top right) -> "Preview on port 8080" to open their unique secure URL.
   - If running locally: Open http://localhost:8080 in their browser.

=============================================================================
First-Time Sign In Setup:
On the first Pitch Deck or Detailed Spreadsheet click, Firebase Auth will ask for permission to save files to your Google Drive. Approve this once, and the document will open in a new tab.

To redeploy after code changes, re-run this script.

EOF
