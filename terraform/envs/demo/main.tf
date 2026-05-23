# ===========================================================================
# Northwood demo — root composition. docs/aws-demo-deployment.md (Part A + B).
# Bring-up order (§8) is mostly handled by Terraform's dependency graph; the
# build/ scripts push images to ECR between `apply` of ECR and the ECS services
# (see terraform/README.md).
# ===========================================================================

locals {
  repo_root = var.repo_root != "" ? var.repo_root : abspath("${path.root}/../../..")

  # The 7 backend services, keyed by schema name. name = "<schema>-service",
  # Service Connect alias = "<schema>", env prefix = upper(schema).
  services = {
    product       = { port = 8081 }
    sales         = { port = 8082 }
    inventory     = { port = 8083 }
    manufacturing = { port = 8084 }
    purchasing    = { port = 8085 }
    finance       = { port = 8086 }
    reporting     = { port = 8087 }
  }

  # The 2 BFFs.
  bffs = {
    "demo-web-ui-bff" = { port = 8080 }
    "erp-web-ui-bff"  = { port = 8089 }
  }

  all_app_names = concat(
    [for k in keys(local.services) : "${k}-service"],
    keys(local.bffs),
  )

  # Endpoints the apps need, resolved from the infra tier.
  kafka_bootstrap          = "${module.infra_ec2.kafka_private_dns}:9092"
  keycloak_issuer_internal = "http://${module.infra_ec2.keycloak_private_dns}:8080/realms/northwood"
  postgres_dns             = module.infra_ec2.postgres_private_dns

  # BFF -> service URLs via Service Connect aliases (NORTHWOOD_BFF_TARGETS_*).
  bff_targets = {
    for k, s in local.services : "NORTHWOOD_BFF_TARGETS_${upper(k)}" => "http://${k}:${s.port}"
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

  demo_bypass_token = var.demo_bypass_token
  bff_client_secret = var.bff_client_secret
}

module "ecr" {
  source      = "../../modules/ecr"
  name_prefix = var.name_prefix
  repo_names  = local.all_app_names
}

module "infra_ec2" {
  source      = "../../modules/infra-ec2"
  name_prefix = var.name_prefix
  subnet_id   = module.network.subnet_ids.infra

  security_group_ids = {
    postgres = module.network.security_group_ids.postgres
    kafka    = module.network.security_group_ids.kafka
    keycloak = module.network.security_group_ids.keycloak
  }

  instance_types    = var.infra_instance_types
  repo_root         = local.repo_root
  load_seed_data    = var.load_seed_data
  keycloak_hostname = var.keycloak_hostname

  postgres_superuser_password = module.secrets.postgres_superuser_password
  keycloak_admin_password     = module.secrets.keycloak_admin_password
  service_db_passwords        = module.secrets.service_db_passwords
}

module "ecs_cluster" {
  source           = "../../modules/ecs-cluster"
  name_prefix      = var.name_prefix
  task_secret_arns = module.secrets.all_task_secret_arns
}

module "alb" {
  source            = "../../modules/alb"
  name_prefix       = var.name_prefix
  vpc_id            = module.network.vpc_id
  subnet_ids        = module.network.alb_subnet_ids
  security_group_id = module.network.security_group_ids.alb
  certificate_arn   = var.certificate_arn

  # demo-bff is the listener default; erp-bff is reached at /erp* (see the OIDC
  # caveat in README — real erp-bff browser login wants host-based routing).
  targets = {
    "demo-web-ui-bff" = { port = 8080, default = true }
    "erp-web-ui-bff"  = { port = 8089, priority = 10, path_patterns = ["/erp", "/erp/*"] }
  }
}
