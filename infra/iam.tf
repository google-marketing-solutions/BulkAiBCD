# ----- Runtime SA: project roles --------------------------------------------

locals {
  runtime_project_roles = [
    "roles/cloudtasks.enqueuer",
    "roles/datastore.user",
    "roles/aiplatform.user",
    "roles/iam.serviceAccountTokenCreator",
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
  ]
}

resource "google_project_iam_member" "runtime_project_roles" {
  for_each = toset(local.runtime_project_roles)

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.runtime.email}"
}

# ----- Runtime SA: bucket-scoped storage admin ------------------------------

resource "google_storage_bucket_iam_member" "runtime_uploads_admin" {
  bucket = google_storage_bucket.uploads.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.runtime.email}"
}

# ----- Cloud Build / compute SA: deploy permissions ------------------------

locals {
  builder_sas = [
    local.cloudbuild_sa,
    local.compute_sa,
  ]
  builder_project_roles = [
    "roles/run.admin",
    "roles/logging.logWriter",
  ]
  builder_project_role_pairs = {
    for pair in setproduct(local.builder_sas, local.builder_project_roles) :
    "${pair[0]}__${pair[1]}" => { sa = pair[0], role = pair[1] }
  }
}

resource "google_project_iam_member" "builder_project_roles" {
  for_each = local.builder_project_role_pairs

  project = var.project_id
  role    = each.value.role
  member  = "serviceAccount:${each.value.sa}"
}

# Each builder SA needs to actAs the runtime SA to deploy a Cloud Run revision
# that runs as runtime.
resource "google_service_account_iam_member" "builders_actas_runtime" {
  for_each = toset(local.builder_sas)

  service_account_id = google_service_account.runtime.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${each.value}"
}

# Runtime SA must actAs itself when calling Cloud Tasks with OIDC tokens
# pointed at its own email.
resource "google_service_account_iam_member" "runtime_actas_self" {
  service_account_id = google_service_account.runtime.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.runtime.email}"
}

# ----- IAP service agent + Cloud Run invokers -------------------------------
# These IAM bindings target the Cloud Run service — which only exists *after*
# Cloud Build's first deploy. install.sh runs Terraform twice: first pass with
# cloud_run_deployed=false (skips these), then Cloud Build submit, then a
# second pass with cloud_run_deployed=true (creates them).

resource "google_cloud_run_service_iam_member" "iap_invoker" {
  count    = var.cloud_run_deployed ? 1 : 0
  location = var.region
  project  = var.project_id
  service  = var.service_name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${local.iap_service_sa}"
}

resource "google_cloud_run_service_iam_member" "runtime_invoker" {
  count    = var.cloud_run_deployed ? 1 : 0
  location = var.region
  project  = var.project_id
  service  = var.service_name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.runtime.email}"
}

# ----- IAP-gated user access (dormant by default) ---------------------------
# Creating this binding *enables* IAP on the Cloud Run service. With IAP live,
# Cloud Tasks' OIDC callbacks to /api/v2/worker/* get blocked by the login
# gate. Only flip var.enable_iap_gate=true once the LB + URL-map routing for
# worker endpoints is in place (follow-up).

resource "google_iap_web_cloud_run_service_iam_member" "users" {
  for_each = (var.cloud_run_deployed && var.enable_iap_gate) ? toset(var.iap_users) : toset([])

  project                = var.project_id
  location               = var.region
  cloud_run_service_name = var.service_name
  role                   = "roles/iap.httpsResourceAccessor"
  member                 = "user:${each.value}"
}

# ----- Plain IAM access (the path actually in use today) --------------------
# --no-allow-unauthenticated is set on the Cloud Run service. The runtime SA's
# run.invoker (above) keeps Cloud Tasks working; teammates need run.invoker
# granted per-user to hit the URL via `gcloud run services proxy`.

resource "google_cloud_run_service_iam_member" "user_invokers" {
  for_each = var.cloud_run_deployed ? toset(var.iap_users) : toset([])

  location = var.region
  project  = var.project_id
  service  = var.service_name
  role     = "roles/run.invoker"
  member   = "user:${each.value}"
}
