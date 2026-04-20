variable "project_id" {
  description = "GCP project ID."
  type        = string
}

variable "region" {
  description = "Default region for regional resources."
  type        = string
  default     = "us-central1"
}

variable "service_name" {
  description = "Cloud Run service name and Artifact Registry repo name."
  type        = string
  default     = "bulkaibcd"
}

variable "runtime_sa_id" {
  description = "Service-account ID for the Cloud Run runtime."
  type        = string
  default     = "bulkaibcd-runtime"
}

variable "queue_id" {
  description = "Cloud Tasks queue id."
  type        = string
  default     = "bulkaibcd-queue"
}

variable "uploads_bucket_name" {
  description = "GCS bucket for video uploads (must be globally unique)."
  type        = string
}

variable "uploads_lifecycle_age_days" {
  description = "Auto-delete uploaded objects after this many days. Vertex AI only needs them during the analysis window."
  type        = number
  default     = 7
}

variable "iap_users" {
  description = "User accounts allowed to access the app through IAP."
  type        = list(string)
  default     = []
}

variable "cors_origins" {
  description = "Origins permitted to PUT to the uploads bucket via signed URL."
  type        = list(string)
  default = [
    "http://localhost:4200",
  ]
}
