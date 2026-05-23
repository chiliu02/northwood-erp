variable "name_prefix" {
  description = "Repos are named <name_prefix>/<app> (e.g. northwood-demo/sales-service)."
  type        = string
}

variable "repo_names" {
  description = "Deployable names — one ECR repo each (7 services + 2 BFFs)."
  type        = list(string)
}
