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

```bash
cd infra
terraform init
terraform plan
terraform apply
```

Then trigger the first image build (Terraform's `next_steps` output prints the exact command).

## Adding more IAP users

Edit `variables.tf` (or override at apply time):

```bash
terraform apply -var='iap_users=["you@example.com","teammate@example.com"]'
```

## Migrating to remote state (recommended)

After the first successful apply, create a state bucket and switch to it:

```bash
gcloud storage buckets create gs://bulkaibcd-tfstate-${PROJECT} \
  --location=us-central1 --uniform-bucket-level-access
```

Uncomment the `backend "gcs"` block in `versions.tf`, then:

```bash
terraform init -migrate-state
```

## Drift between Terraform and the existing imperative `deploy.sh`

The earlier `../deploy.sh` and `../scripts/setup-uploads-bucket.sh` create the same resources. Once Terraform is the source of truth, prefer `terraform apply` and stop using those scripts (they remain useful as documentation of what each resource needs).

If `terraform plan` shows resources as "will be created" that already exist, import them first instead of letting Terraform try to recreate (would conflict):

```bash
terraform import google_service_account.runtime projects/${PROJECT}/serviceAccounts/bulkaibcd-runtime@${PROJECT}.iam.gserviceaccount.com
terraform import google_storage_bucket.uploads bulkaibcd-uploads-${PROJECT}
terraform import google_artifact_registry_repository.images projects/${PROJECT}/locations/us-central1/repositories/bulkaibcd
terraform import google_cloud_tasks_queue.worker projects/${PROJECT}/locations/us-central1/queues/bulkaibcd-queue
terraform import 'google_firestore_database.default' projects/${PROJECT}/databases/(default)
# ... and similar for IAM bindings
```
