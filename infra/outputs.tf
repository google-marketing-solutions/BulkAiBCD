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

output "next_steps" {
  description = "Commands to run after `terraform apply` succeeds."
  value       = <<EOT
Image build and Cloud Run revision rollout still happen via Cloud Build:

  cd .. && gcloud builds submit --config=cloudbuild.yaml \
    --substitutions=_REGION=${var.region},_REPO=${var.service_name},_IMAGE=${var.service_name},_SERVICE=${var.service_name},_RUNTIME_SA=${google_service_account.runtime.email},_CLOUD_TASKS_QUEUE=${var.queue_id},_APP_BACKEND_URL=https://<service-url> \
    --project=${var.project_id}

Terraform manages the underlying infra (APIs, SAs, IAM, Firestore index,
Artifact Registry repo, Cloud Tasks queue, GCS bucket, IAP bindings).
Image releases are independent of Terraform.
EOT
}
