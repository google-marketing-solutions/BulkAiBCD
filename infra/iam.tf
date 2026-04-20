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

# ----- IAP service agent: invoke Cloud Run ----------------------------------

resource "google_cloud_run_service_iam_member" "iap_invoker" {
  location = var.region
  project  = var.project_id
  service  = var.service_name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${local.iap_service_sa}"
}

resource "google_cloud_run_service_iam_member" "runtime_invoker" {
  location = var.region
  project  = var.project_id
  service  = var.service_name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.runtime.email}"
}

# ----- IAP-gated user access -------------------------------------------------

resource "google_iap_web_cloud_run_service_iam_member" "users" {
  for_each = toset(var.iap_users)

  project              = var.project_id
  location             = var.region
  cloud_run_service_name = var.service_name
  role                 = "roles/iap.httpsResourceAccessor"
  member               = "user:${each.value}"
}
