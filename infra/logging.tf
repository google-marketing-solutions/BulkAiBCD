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

# ----- IAP Cloud Audit Logs --------------------------------------------------
# Platform-level record of every request IAP admits *or denies*, stamped with the
# principal's email. This complements the application access log rather than
# duplicating it:
#
#   - IAP audit log  -> proves who reached the app, including denials and requests
#                       for static assets. Cannot be forged by application code.
#   - bulkaibcd-access -> proves which business resource an admitted caller touched.
#
# Attributing a data access needs both halves.

resource "google_project_iam_audit_config" "iap" {
  project = var.project_id
  service = "iap.googleapis.com"

  audit_log_config {
    log_type = "DATA_READ"
  }

  audit_log_config {
    log_type = "DATA_WRITE"
  }
}

# ----- Access log bucket + sink ----------------------------------------------
# The application writes its audit stream under the "bulkaibcd-access" logger.
# Routing it to a dedicated bucket lets it carry a retention period independent
# of ordinary application logs, which default to 30 days.

resource "google_logging_project_bucket_config" "access_log" {
  project        = var.project_id
  location       = "global"
  bucket_id      = "bulkaibcd-access"
  description    = "Attributable access log for Bulk AiBCD. Retention is deliberately long."
  retention_days = var.access_log_retention_days

  # NOTE: `locked = true` makes the retention period irreversible for the life of
  # the bucket -- it cannot be shortened or the bucket deleted until retention
  # expires. That is the stronger guarantee and is worth enabling once the
  # retention period has been agreed, but it is left off here because it cannot
  # be undone by a later `terraform apply`.
}

resource "google_logging_project_sink" "access_log" {
  project     = var.project_id
  name        = "bulkaibcd-access"
  destination = "logging.googleapis.com/${google_logging_project_bucket_config.access_log.id}"

  # The Stackdriver JSON layout (see logback-spring.xml) emits the SLF4J logger
  # name as jsonPayload.logger, which is how the audit stream is isolated from
  # the rest of the service's output.
  filter = <<-EOT
    resource.type="cloud_run_revision"
    resource.labels.service_name="${var.service_name}"
    jsonPayload.logger="bulkaibcd-access"
  EOT

  # Keep the entries in the default _Default bucket too, so existing dashboards
  # and ad-hoc queries continue to work.
  unique_writer_identity = true
}
