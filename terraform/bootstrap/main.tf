# ---------------------------------------------------------------------------
# Terraform remote-state backend bucket.
#
# The envs/demo config manages Secrets Manager values via Terraform, so its
# state file contains those secrets in plaintext. That is exactly why state
# lives in this bucket — private, encrypted, versioned — rather than a local
# terraform.tfstate sitting on a laptop. State locking uses S3's native
# conditional-write lockfile (Terraform >= 1.10, `use_lockfile = true` in the
# backend block), so no DynamoDB table is needed.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "state" {
  bucket = var.state_bucket_name

  # Guardrail: refuse `terraform destroy` from nuking the state bucket out from
  # under every other stack. Remove deliberately if you really mean to tear down.
  lifecycle {
    prevent_destroy = true
  }
}

# Versioning lets you roll back a corrupted or accidentally-truncated state.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Encrypt at rest. SSE-S3 (AES256) is enough for a demo; swap to aws:kms with a
# CMK if you need key rotation / access auditing on the state itself.
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# State must never be public.
resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
