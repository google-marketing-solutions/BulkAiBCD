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

# ----- Cloud Run service -----------------------------------------------------
#
# Terraform creates the service so that its URL exists *before* anything is
# built. That is the whole point of this resource: APP_BACKEND_URL and the
# uploads bucket's CORS origin both need the URL, and previously the only way to
# learn it was to run a full Cloud Build first and read the URL back afterwards.
# Creating the shell of the service here collapses that two-pass dance into one.
#
# Ownership is deliberately split:
#
#   Terraform   -> the service exists, its name/location, and all IAM around it.
#   Cloud Build -> everything inside the revision (image, env vars, CPU, IAP).
#
# The `ignore_changes` block below is what keeps that split honest. Without it
# every `terraform apply` would try to drag the running revision back to the
# placeholder image, which would take the app down.

resource "google_cloud_run_v2_service" "app" {
  name     = var.service_name
  location = var.region
  project  = var.project_id

  # The installer is meant to be re-runnable, and a deliberate `terraform
  # destroy` should not be blocked by a flag the deployer cannot see. Cloud Run
  # services are cheap to recreate; the durable data lives in Firestore and GCS.
  deletion_protection = false

  # Placeholder only. This image is replaced by the first Cloud Build deploy and
  # never actually serves traffic. It exists because Cloud Run will not create a
  # service with no container at all.
  template {
    containers {
      image = "us-docker.pkg.dev/cloudrun/container/hello"
    }
  }

  lifecycle {
    ignore_changes = [
      # Cloud Build owns the revision: image, env vars, service account, CPU,
      # memory and the IAP toggle all arrive via `gcloud run deploy`.
      template,
      # `gcloud` stamps these on every deploy to record what performed it.
      client,
      client_version,
      # Deploy tooling adds its own bookkeeping labels/annotations.
      labels,
      annotations,
    ]
  }

  depends_on = [google_project_service.apis]
}
