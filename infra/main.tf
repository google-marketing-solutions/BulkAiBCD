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
    # Drive / Slides / Sheets for per-user export via Firebase Auth.
    "drive.googleapis.com",
    "slides.googleapis.com",
    "sheets.googleapis.com",
    # Firebase + Identity Platform so the installer can auto-provision the
    # Google sign-in provider without the deployer clicking through a Console form.
    "firebase.googleapis.com",
    "firebasemanagement.googleapis.com",
    "identitytoolkit.googleapis.com",
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

# ----- IAP: OAuth brand + client --------------------------------------------
#
# The OAuth brand is the consent-screen record (one per project). The IAP
# client provides the client_id / client_secret we reuse for Firebase Auth's
# Google provider below, so the installer can wire everything up without
# requiring a Cloud Console click to create a Web OAuth client.

resource "google_iap_brand" "bulkaibcd" {
  support_email     = var.support_email
  application_title = "Bulk AiBCD"
  project           = var.project_id

  depends_on = [google_project_service.apis]
}

resource "google_iap_client" "bulkaibcd" {
  display_name = "${var.service_name}-web"
  brand        = google_iap_brand.bulkaibcd.name
}

# ----- Firebase project + Web App -------------------------------------------

resource "google_firebase_project" "bulkaibcd" {
  provider = google-beta
  project  = var.project_id

  depends_on = [google_project_service.apis]
}

resource "google_firebase_web_app" "bulkaibcd" {
  provider     = google-beta
  project      = var.project_id
  display_name = "Bulk AiBCD"

  depends_on = [google_firebase_project.bulkaibcd]
}

data "google_firebase_web_app_config" "bulkaibcd" {
  provider   = google-beta
  project    = var.project_id
  web_app_id = google_firebase_web_app.bulkaibcd.app_id
}

# ----- Identity Platform: enable Google sign-in provider --------------------
#
# We reuse the IAP-created OAuth client's credentials here so the provider can
# be enabled entirely through Terraform — no Firebase Console toggle required.
# install.sh verifies after apply and falls back to a deep-link-to-Console if
# GCIP rejects the credentials (see plan Phase 6 step 5).

resource "google_identity_platform_config" "bulkaibcd" {
  project = var.project_id

  sign_in {
    allow_duplicate_emails = false

    email {
      enabled = false
    }
  }

  depends_on = [google_firebase_project.bulkaibcd]
}

resource "google_identity_platform_default_supported_idp_config" "google" {
  project       = var.project_id
  enabled       = true
  idp_id        = "google.com"
  client_id     = google_iap_client.bulkaibcd.client_id
  client_secret = google_iap_client.bulkaibcd.secret

  depends_on = [google_identity_platform_config.bulkaibcd]
}
