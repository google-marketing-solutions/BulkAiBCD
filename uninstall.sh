#!/usr/bin/env bash
#
# Bulk AiBCD one-command uninstaller.
#
# Designed to clean up resources created by install.sh and Terraform on the user's cloud.
#
# Usage:
#   ./uninstall.sh <PROJECT_ID> [--region us-central1]
#
set -euo pipefail

usage() {
  cat <<EOF >&2
Usage: $0 <PROJECT_ID> [--region REGION]

  <PROJECT_ID>    GCP project that hosts the deployment.
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

# -----------------------------------------------------------------------------
banner "Preflight"
# -----------------------------------------------------------------------------

command -v gcloud >/dev/null 2>&1 || fail "gcloud not found."
command -v terraform >/dev/null 2>&1 || fail "terraform not found."

ACTIVE_ACCOUNT="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | head -n1)"
[[ -n "${ACTIVE_ACCOUNT}" ]] || fail "No active gcloud auth. Run: gcloud auth login"
info "gcloud authed as: ${ACTIVE_ACCOUNT}"
info "Project:          ${PROJECT}"
info "Region:           ${REGION}"

gcloud projects describe "${PROJECT}" >/dev/null 2>&1 \
  || fail "Project '${PROJECT}' not accessible with '${ACTIVE_ACCOUNT}'."
gcloud config set project "${PROJECT}" >/dev/null

# -----------------------------------------------------------------------------
banner "Emptying GCS bucket"
# -----------------------------------------------------------------------------
UPLOADS_BUCKET="bulkaibcd-uploads-${PROJECT}"
if gcloud storage buckets describe "gs://${UPLOADS_BUCKET}" >/dev/null 2>&1; then
  info "Emptying bucket gs://${UPLOADS_BUCKET} to allow deletion..."
  # Use || true so it doesn't fail if the bucket is already empty
  gcloud storage rm --recursive "gs://${UPLOADS_BUCKET}/**" 2>/dev/null || true
else
  info "Bucket gs://${UPLOADS_BUCKET} not found or already deleted."
fi

# -----------------------------------------------------------------------------
banner "Deleting Cloud Run service"
# -----------------------------------------------------------------------------
if gcloud run services describe bulkaibcd --region="${REGION}" --project="${PROJECT}" >/dev/null 2>&1; then
  info "Deleting Cloud Run service 'bulkaibcd' in region ${REGION}..."
  gcloud run services delete bulkaibcd --region="${REGION}" --project="${PROJECT}" --quiet
  info "Cloud Run service deleted."
else
  info "Cloud Run service 'bulkaibcd' not found or already deleted."
fi

# -----------------------------------------------------------------------------
banner "Terraform destroy"
# -----------------------------------------------------------------------------
info "Running terraform destroy to remove infrastructure..."
# Ensure infra is initialized
terraform -chdir=infra init -upgrade -input=false >/dev/null

QUEUE_ID="bulkaibcd-queue"

if [[ -f infra/terraform.tfvars ]]; then
  info "Using existing infra/terraform.tfvars"
else
  info "Creating temporary infra/terraform.tfvars for destroy..."
  cat > infra/terraform.tfvars <<EOF
project_id           = "${PROJECT}"
region               = "${REGION}"
support_email        = "${ACTIVE_ACCOUNT}"
iap_users            = ["${ACTIVE_ACCOUNT}"]
uploads_bucket_name  = "${UPLOADS_BUCKET}"
queue_id             = "${QUEUE_ID}"
cloud_run_deployed   = false
cors_origins         = []
EOF
fi

terraform -chdir=infra destroy -auto-approve -input=false

info "Terraform destroy completed."

# -----------------------------------------------------------------------------
banner "Local Cleanup"
# -----------------------------------------------------------------------------
info "Removing local state and generated files..."

rm -f ui/src/environments/firebase-config.json
rm -f infra/terraform.tfvars
rm -rf infra/.terraform
rm -f infra/.terraform.lock.hcl
rm -f infra/terraform.tfstate
rm -f infra/terraform.tfstate.backup

info "Local files cleaned up."

# -----------------------------------------------------------------------------
banner "Done"
# -----------------------------------------------------------------------------
cat <<EOF

Bulk AiBCD resources have been completely uninstalled.

Note: Enabled APIs (like Cloud Run, Firestore, etc.) were NOT disabled,
as they might be in use by other applications in your GCP project. If you wish
to completely remove them, you can disable them manually in the GCP Console.

EOF
