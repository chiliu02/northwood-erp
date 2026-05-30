# Bootstrap uses LOCAL state by design — it is the chicken-and-egg breaker that
# creates the S3 bucket the *real* config (envs/demo) then uses as its backend.
# Run this once, before `terraform init` in envs/demo. Keep the local
# terraform.tfstate this produces in version control or re-import if lost.
terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "northwood"
      ManagedBy = "terraform"
      Component = "tf-state-bootstrap"
    }
  }
}
