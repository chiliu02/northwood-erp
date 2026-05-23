variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  description = "The two public subnets (ALB spans >=2 AZs)."
  type        = list(string)
}

variable "security_group_id" {
  description = "alb-sg."
  type        = string
}

variable "certificate_arn" {
  description = "ACM cert ARN for HTTPS. Empty => HTTP-only on :80 (fine for a throwaway demo)."
  type        = string
  default     = ""
}

variable "targets" {
  description = <<-EOT
    Backend targets behind the ALB (the 2 BFFs). Keyed by name. `priority` and
    `match` apply to non-default targets as listener rules; the `default` target
    gets the listener's default action (no rule).
  EOT
  type = map(object({
    port              = number
    health_check_path = optional(string, "/actuator/health")
    default           = optional(bool, false)
    priority          = optional(number)
    host_headers      = optional(list(string))
    path_patterns     = optional(list(string))
  }))
}
