variable "name" {
  description = "App name (e.g. sales-service). Used for the service, task family, log group, and Service Connect alias."
  type        = string
}

variable "region" {
  type = string
}

variable "cluster_arn" {
  type = string
}

variable "service_connect_namespace_arn" {
  type = string
}

variable "subnet_ids" {
  description = "Subnet(s) the task ENI lands in (web subnet for BFFs, app subnet for services)."
  type        = list(string)
}

variable "security_group_id" {
  description = "web-sg for BFFs, app-sg for services."
  type        = string
}

variable "execution_role_arn" {
  type = string
}

variable "task_role_arn" {
  type = string
}

variable "image" {
  description = "Full image reference: <ecr-repo-url>:<tag>."
  type        = string
}

variable "container_port" {
  type = number
}

variable "cpu" {
  type    = number
  default = 512
}

variable "memory" {
  type    = number
  default = 1024
}

variable "cpu_architecture" {
  description = "X86_64 (buildpack images built on x86) or ARM64."
  type        = string
  default     = "X86_64"
}

variable "desired_count" {
  type    = number
  default = 1
}

variable "environment" {
  description = "Plain environment variables (name => value)."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Secret env vars (name => Secrets Manager ARN), injected as secrets[]."
  type        = map(string)
  default     = {}
}

variable "service_connect_alias" {
  description = "Short DNS name services advertise under Service Connect (e.g. \"product\", so BFFs call http://product:8081). Defaults to the task name."
  type        = string
  default     = ""
}

variable "advertise" {
  description = "True for the 7 services (advertise a Service Connect alias). False for BFFs (clients only)."
  type        = bool
  default     = true
}

variable "alb_target_group_arn" {
  description = "If set, register this service's tasks with the ALB target group (BFFs only)."
  type        = string
  default     = ""
}

variable "log_retention_days" {
  type    = number
  default = 14
}
