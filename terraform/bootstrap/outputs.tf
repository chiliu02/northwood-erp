output "state_bucket_name" {
  description = "Name of the state bucket. Copy into envs/demo/backend.tf (bucket = ...)."
  value       = aws_s3_bucket.state.id
}

output "region" {
  description = "Region the state bucket lives in. Use the same region in envs/demo/backend.tf."
  value       = var.region
}
