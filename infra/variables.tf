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

variable "project_id" {
  description = "GCP project ID. Required — supply via install.sh or terraform.tfvars."
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
  description = "GCS bucket name for video uploads. Must be globally unique. install.sh defaults to bulkaibcd-uploads-<PROJECT_ID>."
  type        = string
}

variable "uploads_lifecycle_age_days" {
  description = "Auto-delete uploaded objects after this many days. Vertex AI only needs them during the analysis window."
  type        = number
  default     = 7
}

variable "iap_users" {
  description = "User accounts allowed to access the app. install.sh seeds this with the current gcloud account; add more via terraform.tfvars or gcloud IAM grants."
  type        = list(string)
  default     = []
}

variable "support_email" {
  description = "Support email shown on the OAuth consent screen. install.sh defaults to the gcloud-authenticated account."
  type        = string
}

variable "cloud_run_deployed" {
  description = "Set to true on the second terraform pass — after Cloud Build has created the Cloud Run service — so Cloud Run IAM bindings can attach. install.sh handles this automatically."
  type        = bool
  default     = false
}

variable "enable_iap_gate" {
  description = <<-EOT
    Turn on IAP in front of the Cloud Run service. Default false because enabling IAP
    blocks Cloud Tasks OIDC callbacks — the worker /api/v2/worker/* paths become
    unreachable until a load-balancer with URL-map rules routes around IAP. Flip to
    true only after that LB is in place. See README's "Access control" section.
  EOT
  type    = bool
  default = false
}

variable "cors_origins" {
  description = "Origins permitted to PUT to the uploads bucket via signed URL. install.sh seeds this with localhost + the deployed Cloud Run URL."
  type        = list(string)
  default = [
    "http://localhost:4200",
    "http://localhost:8080",
  ]
}
