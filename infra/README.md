# Bulk AiBCD — Terraform infrastructure

This directory declares all the long-lived infra for the deployed app:

- API enablement (Run, Cloud Build, Cloud Tasks, Firestore, Vertex AI, Artifact Registry, IAP, Storage, IAM, CRM)
- Firestore database + composite index for `analyses(requesterId, createdAt desc)`
- Artifact Registry Docker repository
- Cloud Tasks queue
- Runtime service account + project IAM (Datastore, Cloud Tasks, Vertex AI, etc.)
- GCS uploads bucket with **7-day delete lifecycle**, CORS for browser PUTs, and `roles/storage.objectAdmin` for the runtime SA
- Cloud Build / compute service-account permissions to deploy Cloud Run + actAs the runtime SA
- IAP service-agent invoker on Cloud Run
- IAP user accessor bindings for the people in `var.iap_users`

What is **not** in Terraform:
- The container image build / Cloud Run revision rollout. Those still happen via `gcloud builds submit --config=../cloudbuild.yaml ...` (or you can wire a Cloud Build trigger). Image rollouts shouldn't go through `terraform apply`.

## First-time apply

To deploy the infrastructure, do **not** run Terraform manually. Instead, use the automated installation script from the root of the repository. It handles variable injection, the required two-pass Terraform apply, and the Cloud Build sequence:

```bash
cd ..
./install.sh YOUR_GCP_PROJECT_ID
```

## Adding more IAP users

Edit `variables.tf` (or add them via `infra/terraform.tfvars` if it was generated), then re-run the `install.sh` script to apply the changes safely.

## Migrating to remote state (recommended)

After the first successful apply using `install.sh`, create a state bucket and switch to it:

```bash
gcloud storage buckets create gs://bulkaibcd-tfstate-${PROJECT} \
  --location=us-central1 --uniform-bucket-level-access
```

Uncomment the `backend "gcs"` block in `versions.tf`, then:

```bash
terraform init -migrate-state
```
