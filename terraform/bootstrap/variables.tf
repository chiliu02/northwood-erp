variable "region" {
  description = "AWS region for the state bucket. Default Sydney — the demo's seed data is AUD/Australian."
  type        = string
  default     = "ap-southeast-2"
}

variable "state_bucket_name" {
  description = <<-EOT
    Globally-unique S3 bucket name for Terraform remote state. S3 bucket names
    share one global namespace, so this must be unique across all of AWS — e.g.
    "northwood-tfstate-<account-id>" or "northwood-tfstate-<your-initials>".

    Defaults to this demo's bucket; it MUST match the `bucket` literal in
    envs/demo/backend.tf (backend blocks can't read variables). For your own
    deployment, override here with -var and edit backend.tf to the same name.
  EOT
  type        = string
  default     = "northwood-tfstate-chiliu02"
}
