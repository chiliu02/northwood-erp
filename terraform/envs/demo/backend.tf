# Remote state in the bucket created by terraform/bootstrap.
#
# Backend config can't use variables, so fill `bucket` in with the name you
# passed to bootstrap (or override all of these at init time:
#   terraform init -backend-config="bucket=northwood-tfstate-123456789012")
#
# `use_lockfile = true` is S3-native state locking (Terraform >= 1.10) — no
# DynamoDB table required. On older Terraform, drop that line and add
# `dynamodb_table = "<lock-table>"` (and create the table in bootstrap).
terraform {
  backend "s3" {
    bucket       = "REPLACE_ME-northwood-tfstate"
    key          = "northwood/demo/terraform.tfstate"
    region       = "ap-southeast-2"
    encrypt      = true
    use_lockfile = true
  }
}
