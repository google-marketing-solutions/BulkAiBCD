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

# ----- Runtime SA: project roles --------------------------------------------

locals {
  runtime_project_roles = [
    "roles/cloudtasks.enqueuer",
    "roles/datastore.user",
    "roles/aiplatform.user",
    "roles/iam.serviceAccountTokenCreator",
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
    "roles/iap.httpsResourceAccessor",
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
    "roles/storage.admin",
    "roles/artifactregistry.writer",
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
# These IAM bindings target the Cloud Run service. It is created by Terraform
# itself (see run.tf), so they can always attach — there is no longer a pass in
# which the service is absent. The explicit depends_on pins the ordering, since
# these resources reference the service by name rather than by attribute.

resource "google_cloud_run_service_iam_member" "runtime_invoker" {
  location = var.region
  project  = var.project_id
  service  = var.service_name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.runtime.email}"

  depends_on = [google_cloud_run_v2_service.app]
}

# ----- IAP-gated user access -------------------------------------------------
# IAP *is* live: cloudbuild.yaml deploys the service with `--iap`, and Cloud Tasks
# reaches the worker endpoints by minting OIDC tokens whose audience is the IAP
# OAuth client (see CloudTasksQueueClient), so the login gate does not block them.
# End-user access is granted through roles/iap.httpsResourceAccessor in main.tf.


# ----- Plain IAM access ------------------------------------------------------
# --no-allow-unauthenticated is also set, so the Cloud Run IAM policy remains the
# inner gate: IAP's service agent and the runtime SA both need run.invoker. The
# per-user grants below additionally allow `gcloud run services proxy`.


resource "google_cloud_run_service_iam_member" "user_invokers" {
  for_each = toset(var.iap_users)

  location = var.region
  project  = var.project_id
  service  = var.service_name
  role     = "roles/run.invoker"
  member   = "user:${each.value}"

  depends_on = [google_cloud_run_v2_service.app]
}
