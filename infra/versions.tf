terraform {
  required_version = ">= 1.5.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 5.30.0"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = ">= 5.30.0"
    }
  }

  # Local state by default. For multi-deployer setups, uncomment the backend
  # block below, create the bucket out-of-band, and run `terraform init -migrate-state`.
  # backend "gcs" {
  #   bucket = "bulkaibcd-tfstate-YOUR_PROJECT_ID"
  #   prefix = "infra"
  # }
}
