provider "google" {
  project = var.project_id
  region  = var.region
}

provider "google-beta" {
  project = var.project_id
  region  = var.region
}

data "google_project" "this" {
  project_id = var.project_id
}

locals {
  runtime_sa_email = "${var.runtime_sa_id}@${var.project_id}.iam.gserviceaccount.com"
  cloudbuild_sa    = "${data.google_project.this.number}@cloudbuild.gserviceaccount.com"
  compute_sa       = "${data.google_project.this.number}-compute@developer.gserviceaccount.com"
  iap_service_sa   = "service-${data.google_project.this.number}@gcp-sa-iap.iam.gserviceaccount.com"
}

# ----- Required APIs ---------------------------------------------------------

resource "google_project_service" "apis" {
  for_each = toset([
    "run.googleapis.com",
    "cloudbuild.googleapis.com",
    "cloudtasks.googleapis.com",
    "firestore.googleapis.com",
    "aiplatform.googleapis.com",
    "artifactregistry.googleapis.com",
    "iap.googleapis.com",
    "storage.googleapis.com",
    "iam.googleapis.com",
    "cloudresourcemanager.googleapis.com",
  ])
  service            = each.value
  disable_on_destroy = false
}

# ----- Firestore (default, native mode) --------------------------------------

resource "google_firestore_database" "default" {
  project     = var.project_id
  name        = "(default)"
  location_id = var.region
  type        = "FIRESTORE_NATIVE"

  depends_on = [google_project_service.apis]
}

# Composite index so InputController.listAnalyses works.
resource "google_firestore_index" "analyses_by_requester_created" {
  project    = var.project_id
  collection = "analyses"

  fields {
    field_path = "requesterId"
    order      = "ASCENDING"
  }
  fields {
    field_path = "createdAt"
    order      = "DESCENDING"
  }
  fields {
    field_path = "__name__"
    order      = "DESCENDING"
  }

  depends_on = [google_firestore_database.default]
}

# ----- Artifact Registry -----------------------------------------------------

resource "google_artifact_registry_repository" "images" {
  repository_id = var.service_name
  location      = var.region
  format        = "DOCKER"
  description   = "Container images for ${var.service_name}"

  depends_on = [google_project_service.apis]
}

# ----- Cloud Tasks queue -----------------------------------------------------

resource "google_cloud_tasks_queue" "worker" {
  name     = var.queue_id
  location = var.region

  rate_limits {
    max_dispatches_per_second = 5
    max_concurrent_dispatches = 50
  }

  retry_config {
    max_attempts  = 3
    min_backoff   = "5s"
    max_backoff   = "60s"
    max_doublings = 3
  }

  depends_on = [google_project_service.apis]
}

# ----- Runtime service account ----------------------------------------------

resource "google_service_account" "runtime" {
  account_id   = var.runtime_sa_id
  display_name = "Bulk AiBCD Cloud Run runtime"

  depends_on = [google_project_service.apis]
}

# ----- GCS uploads bucket (videos in-flight only; lifecycle deletes them) ----

resource "google_storage_bucket" "uploads" {
  name                        = var.uploads_bucket_name
  location                    = var.region
  uniform_bucket_level_access = true
  force_destroy               = false

  lifecycle_rule {
    action {
      type = "Delete"
    }
    condition {
      age = var.uploads_lifecycle_age_days
    }
  }

  cors {
    origin          = var.cors_origins
    method          = ["PUT", "GET", "HEAD", "OPTIONS"]
    response_header = ["Content-Type", "x-goog-resumable"]
    max_age_seconds = 3600
  }

  depends_on = [google_project_service.apis]
}
