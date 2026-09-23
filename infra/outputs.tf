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

output "runtime_service_account" {
  description = "Email of the Cloud Run runtime service account."
  value       = google_service_account.runtime.email
}

output "uploads_bucket" {
  description = "Name of the GCS uploads bucket."
  value       = google_storage_bucket.uploads.name
}

output "artifact_registry_repo" {
  description = "Path of the Artifact Registry repo for the container image."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
}

output "cloud_tasks_queue" {
  description = "Cloud Tasks queue name."
  value       = google_cloud_tasks_queue.worker.name
}

# ----- Firebase Web config (consumed by install.sh to bake into Angular) ----

output "firebase_web_config" {
  description = "Public Firebase config the UI bundle reads from firebase-config.json."
  value = {
    apiKey     = data.google_firebase_web_app_config.bulkaibcd.api_key
    authDomain = data.google_firebase_web_app_config.bulkaibcd.auth_domain
    projectId  = var.project_id
    appId      = google_firebase_web_app.bulkaibcd.app_id
  }
}

output "cloud_run_url" {
  description = "Public URL of the Cloud Run service. Known before the first build, because Terraform creates the service."
  value       = google_cloud_run_v2_service.app.uri
}

output "next_steps" {
  description = "Commands to run after `terraform apply` succeeds."
  value       = <<EOT
Terraform has created the Cloud Run service, but it is still serving the
placeholder image. Build and roll out the real one:

  cd .. && gcloud builds submit --config=cloudbuild.yaml \
    --substitutions=_REGION=${var.region},_RUNTIME_SA=${google_service_account.runtime.email},_UPLOADS_BUCKET=${var.uploads_bucket_name},_CLOUD_TASKS_QUEUE=${var.queue_id},_APP_BACKEND_URL=${google_cloud_run_v2_service.app.uri} \
    --project=${var.project_id}

Terraform owns the surrounding infrastructure (APIs, SAs, IAM, Firestore index,
Artifact Registry repo, Cloud Tasks queue, GCS bucket, and the existence of the
Cloud Run service). Cloud Build owns the contents of each revision, so image
releases remain independent of Terraform.
EOT
}
