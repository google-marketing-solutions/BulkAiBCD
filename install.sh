#!/usr/bin/env bash
# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# Bulk AiBCD one-command installer.
#
# Designed to run inside Cloud Shell (gcloud + terraform pre-installed, user
# already auth'd). Provisions everything — APIs, Firestore, SA, Cloud Tasks,
# GCS, IAP, Firebase project + Google sign-in provider, Cloud Run service —
# then builds the container and deploys.
#
# Runs one `terraform apply` and one Cloud Build. Terraform creates the Cloud
# Run service up front (with a placeholder image) so that its URL is known
# before the build starts; the build then produces the real image and the final
# revision. Terraform owns the surrounding infrastructure and the existence of
# the service, Cloud Build owns the contents of each revision.
#
# A first install stops once to ask for an IAP OAuth Client ID, which is then
# saved to infra/.install-state. Later runs reuse it and need no input at all.
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

ensure_terraform_installed() {
  if command -v terraform >/dev/null 2>&1; then
    if terraform version 2>&1 | grep -qi "Terraform v"; then
      return 0
    fi
  fi

  info "Terraform not found or is a dummy wrapper. Attempting to install..."
  if ! command -v apt-get >/dev/null 2>&1; then
    fail "apt-get not found. Please install Terraform 1.5+ manually."
  fi

  wget -qO - https://apt.releases.hashicorp.com/gpg | sudo gpg --yes --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(grep -oP '(?<=UBUNTU_CODENAME=).*' /etc/os-release || lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/hashicorp.list > /dev/null
  sudo apt-get update -yq >/dev/null
  sudo apt-get install terraform -yq >/dev/null

  if ! terraform version 2>&1 | grep -qi "Terraform v"; then
    fail "Failed to install Terraform. Please install it manually."
  fi

  info "Terraform installed successfully."
  if [[ -n "${CLOUD_SHELL:-}" ]]; then
    echo 'wget -qO - https://apt.releases.hashicorp.com/gpg | sudo gpg --yes --dearmor -o /usr/share/keyrings/hashicorp-archive-keyring.gpg; echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/hashicorp-archive-keyring.gpg] https://apt.releases.hashicorp.com $(grep -oP '\''(?<=UBUNTU_CODENAME=).*'\'' /etc/os-release || lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/hashicorp.list > /dev/null; sudo apt-get update -yq > /dev/null; sudo apt-get install terraform -yq > /dev/null' >> "$HOME/.customize_environment"
    info "Saved Terraform installation to ~/.customize_environment"
  fi
}

# -----------------------------------------------------------------------------
banner "Preflight"
# -----------------------------------------------------------------------------

command -v gcloud >/dev/null 2>&1 || fail "gcloud not found. Use Cloud Shell or install the gcloud CLI."
ensure_terraform_installed

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
banner "Terraform"
# -----------------------------------------------------------------------------
# Terraform creates the Cloud Run service itself (with a placeholder image), so
# the service URL exists before anything is built. That is what allows a single
# apply and a single build: previously the URL could only be learned by running
# a full build first, which forced a second pass of both.

UPLOADS_BUCKET="bulkaibcd-uploads-${PROJECT}"
QUEUE_ID="bulkaibcd-queue"
SERVICE_NAME="bulkaibcd"
STATE_FILE="infra/.install-state"

# Restore the IAP OAuth Client ID captured on a previous run. This is read
# *before* terraform.tfvars is rewritten — the previous version of this script
# stored it in tfvars and then overwrote that file moments later, so the value
# was always lost and the prompt fired on every single run.
IAP_CLIENT_ID=""
if [[ -f "${STATE_FILE}" ]]; then
  IAP_CLIENT_ID="$(grep -E '^iap_client_id=' "${STATE_FILE}" | head -n1 | cut -d= -f2-)"
  if [[ -n "${IAP_CLIENT_ID}" ]]; then
    info "Reusing saved IAP OAuth Client ID."
  fi
fi

cat > infra/terraform.tfvars <<EOF
project_id           = "${PROJECT}"
region               = "${REGION}"
support_email        = "${ACTIVE_ACCOUNT}"
iap_users            = ["${ACTIVE_ACCOUNT}"]
uploads_bucket_name  = "${UPLOADS_BUCKET}"
queue_id             = "${QUEUE_ID}"
EOF
info "Wrote infra/terraform.tfvars"

# No -upgrade: provider versions are pinned in versions.tf and locked in
# .terraform.lock.hcl. Upgrading on every run meant each deploy silently picked
# up whatever provider Google had released most recently.
terraform -chdir=infra init -input=false

# A service left behind by an older version of this installer — or by a direct
# Cloud Build deploy — exists in the project but not in Terraform state, which
# would make apply fail with "already exists". Adopt it instead.
if gcloud run services describe "${SERVICE_NAME}" \
     --region="${REGION}" --project="${PROJECT}" >/dev/null 2>&1; then
  IN_STATE="$(terraform -chdir=infra state list 2>/dev/null \
    | grep -Fx 'google_cloud_run_v2_service.app' || true)"
  if [[ -z "${IN_STATE}" ]]; then
    info "Existing Cloud Run service found outside Terraform state — importing."
    terraform -chdir=infra import -input=false \
      google_cloud_run_v2_service.app \
      "projects/${PROJECT}/locations/${REGION}/services/${SERVICE_NAME}" >/dev/null \
      || fail "Could not import the existing '${SERVICE_NAME}' service. Import it manually, or delete the service and re-run."
    info "Imported."
  fi
fi

terraform -chdir=infra apply -auto-approve -input=false

RUNTIME_SA="$(tf_out runtime_service_account)"
[[ -n "${RUNTIME_SA}" ]] || fail "Terraform didn't surface runtime_service_account output."
info "Runtime SA: ${RUNTIME_SA}"

CLOUD_RUN_URL="$(tf_out cloud_run_url)"
[[ -n "${CLOUD_RUN_URL}" ]] || fail "Terraform didn't surface cloud_run_url output."
info "Cloud Run URL: ${CLOUD_RUN_URL}"

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
banner "Cloud Build"
# -----------------------------------------------------------------------------
# One build, one deploy. APP_BACKEND_URL is already known because Terraform
# created the service, and IAP_CLIENT_ID is passed too whenever a previous run
# saved one — in which case this build produces the final, fully-wired revision
# and nothing below needs to run.

gcloud builds submit \
  --config=cloudbuild.yaml \
  --project="${PROJECT}" \
  --substitutions="_REGION=${REGION},_RUNTIME_SA=${RUNTIME_SA},_UPLOADS_BUCKET=${UPLOADS_BUCKET},_CLOUD_TASKS_QUEUE=${QUEUE_ID},_APP_BACKEND_URL=${CLOUD_RUN_URL},_IAP_CLIENT_ID=${IAP_CLIENT_ID}"

# -----------------------------------------------------------------------------
banner "IAP OAuth"
# -----------------------------------------------------------------------------
# Normally only reached on a first install. IAP must be switched on before the
# console will mint a Custom OAuth client, and that happens as part of the
# deploy above, so this prompt cannot come any earlier.

if [[ -n "${IAP_CLIENT_ID}" ]]; then
  info "IAP OAuth Client ID already configured."
else
  echo
  echo "   Please generate your Custom OAuth Client ID from IAP:"
  echo "   1. Open: https://console.cloud.google.com/security/iap?project=${PROJECT}"
  echo "   2. Locate '${SERVICE_NAME}' in the list."
  echo "   3. Click Actions (⋮) > Settings next to the service."
  echo "   4. Select 'Custom OAuth' > 'Auto-generated credentials'."
  echo "   5. Copy the Client ID and press 'Save'."
  echo

  while [[ -z "${IAP_CLIENT_ID}" ]]; do
    read -r -p "   Please paste your IAP OAuth Client ID here: " IAP_CLIENT_ID
    if [[ -z "${IAP_CLIENT_ID}" ]]; then
      echo "      ❌ Client ID cannot be empty."
    fi
  done

  cat > "${STATE_FILE}" <<EOF
# Written by install.sh so that redeploys do not have to ask again.
# Safe to delete — you will simply be prompted on the next run.
iap_client_id=${IAP_CLIENT_ID}
EOF
  info "Saved to ${STATE_FILE}; future runs will not ask again."

  # Only an environment variable changed, so update the running service in
  # place. Rebuilding the identical source to change one variable is what made
  # the old two-pass installer take roughly twice as long as it needed to.
  info "Applying IAP_CLIENT_ID to the running service..."
  gcloud run services update "${SERVICE_NAME}" \
    --region="${REGION}" \
    --project="${PROJECT}" \
    --update-env-vars="IAP_CLIENT_ID=${IAP_CLIENT_ID}" \
    --quiet >/dev/null
  info "Applied."
fi

# -----------------------------------------------------------------------------
banner "Enforcing CORS on uploads bucket"
# -----------------------------------------------------------------------------

cat <<EOF > cors.json
[
  {
    "origin": ["*"],
    "method": ["GET", "PUT", "POST", "HEAD", "OPTIONS", "DELETE"],
    "responseHeader": ["Content-Type", "x-goog-resumable", "Authorization", "X-Requested-With", "Origin", "Accept", "*"],
    "maxAgeSeconds": 3600
  }
]
EOF
gcloud storage buckets update "gs://${UPLOADS_BUCKET}" --cors-file=cors.json
rm -f cors.json
info "CORS applied to gs://${UPLOADS_BUCKET}"

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
