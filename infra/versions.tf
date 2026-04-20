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

  # Uncomment + create the bucket once before `terraform init -migrate-state`
  # backend "gcs" {
  #   bucket = "bulkaibcd-tfstate-YOUR_PROJECT_ID"
  #   prefix = "infra"
  # }
}
