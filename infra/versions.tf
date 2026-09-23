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

terraform {
  required_version = ">= 1.5.0"

  # Pinned to a major version on purpose. A bare lower bound such as
  # ">= 5.30.0" accepts every future major release, which means a provider
  # release on Google's side can change or break this deployment with no commit
  # on ours. Bump this deliberately, and re-test, rather than drifting.
  #
  # Keep .terraform.lock.hcl committed alongside this so every deployer resolves
  # byte-identical providers.
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 8.3"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "~> 8.3"
    }
  }

  # Local state by default. For multi-deployer setups, uncomment the backend
  # block below, create the bucket out-of-band, and run `terraform init -migrate-state`.
  # backend "gcs" {
  #   bucket = "bulkaibcd-tfstate-YOUR_PROJECT_ID"
  #   prefix = "infra"
  # }
}
