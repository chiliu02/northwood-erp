# ===========================================================================
# Northwood demo — root composition. docs/aws-deployment.html (the minimal,
# EC2 + docker-run, 3-tier topology). No ALB, no ECS/Fargate, no managed NAT:
#
#   public subnet : web EC2  (erp-web-ui-bff + Keycloak)        — public IP
#   app subnet    : app EC2  (the 7 services)                   — private
#   infra subnet  : data EC2 (Postgres + Kafka + observability) — private
#   + a NAT instance in the public subnet for private egress.
#
# Build/push the 8 app images to ECR first (see terraform/README), then apply.
# ===========================================================================

data "aws_caller_identity" "current" {}

locals {
  repo_root = var.repo_root != "" ? var.repo_root : abspath("${path.root}/../../..")

  # The 7 backend services, keyed by schema name => listen port.
  services = {
    product       = 8081
    sales         = 8082
    inventory     = 8083
    manufacturing = 8084
    purchasing    = 8085
    finance       = 8086
    reporting     = 8087
  }

  bff_name = "erp-web-ui-bff"

  # ECR repos: one per app image (the 7 services + erp-web-ui-bff).
  app_repo_names = concat([for k in keys(local.services) : "${k}-service"], [local.bff_name])

  ecr_registry = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com"

  # Pinned static private IPs (must sit inside the network module's subnet CIDRs).
  private_ips = {
    web   = "10.0.1.10"
    app   = "10.0.2.10"
    infra = "10.0.3.10"
  }
}

module "network" {
  source      = "../../modules/network"
  name_prefix = var.name_prefix
}

module "secrets" {
  source        = "../../modules/secrets"
  name_prefix   = var.name_prefix
  service_names = keys(local.services)

  bff_client_secret = var.bff_client_secret
}

module "ecr" {
  source      = "../../modules/ecr"
  name_prefix = var.name_prefix
  repo_names  = local.app_repo_names
}

module "compute" {
  source = "../../modules/infra-ec2"

  name_prefix = var.name_prefix
  subnet_ids  = module.network.subnet_ids
  security_group_ids = {
    web   = module.network.security_group_ids.web
    app   = module.network.security_group_ids.app
    infra = module.network.security_group_ids.infra
  }
  private_ips = local.private_ips

  ecr_registry        = local.ecr_registry
  ecr_repository_arns = [for arn in module.ecr.repository_arns : arn]
  image_prefix        = var.name_prefix
  image_tag           = var.image_tag

  services = local.services
  bff_name = local.bff_name

  repo_root            = local.repo_root
  load_seed_data       = var.load_seed_data
  keycloak_hostname    = var.keycloak_hostname
  enable_observability = var.enable_observability

  postgres_superuser_password = module.secrets.postgres_superuser_password
  keycloak_admin_password     = module.secrets.keycloak_admin_password
  bff_client_secret           = var.bff_client_secret
  service_db_passwords        = module.secrets.service_db_passwords
}
