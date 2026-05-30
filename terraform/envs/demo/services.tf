# ===========================================================================
# The 9 deployables, each instantiating the reusable ecs-service module.
# Services (app subnet, advertise a Service Connect alias) and BFFs (web subnet,
# ALB-fronted, Service Connect clients) are two for_each blocks over the maps
# defined in main.tf.
# ===========================================================================

module "services" {
  source   = "../../modules/ecs-service"
  for_each = local.services

  name                          = "${each.key}-service"
  service_connect_alias         = each.key
  region                        = var.region
  cluster_arn                   = module.ecs_cluster.cluster_arn
  service_connect_namespace_arn = module.ecs_cluster.service_connect_namespace_arn
  execution_role_arn            = module.ecs_cluster.execution_role_arn
  task_role_arn                 = module.ecs_cluster.task_role_arn

  subnet_ids        = [module.network.subnet_ids.app]
  security_group_id = module.network.security_group_ids.app

  image          = "${module.ecr.repository_urls["${each.key}-service"]}:${var.image_tag}"
  container_port = each.value.port
  cpu            = var.service_cpu
  memory         = var.service_memory
  desired_count  = lookup(var.service_desired_counts, each.key, 1)
  advertise      = true

  environment = merge(local.telemetry_env, {
    SPRING_PROFILES_ACTIVE       = "kafka" # mandatory: outbox publisher + consumers gate on it
    KAFKA_BOOTSTRAP_SERVERS      = local.kafka_bootstrap
    KEYCLOAK_ISSUER_URI          = local.keycloak_issuer_internal
    "${upper(each.key)}_DB_URL"  = "jdbc:postgresql://${local.postgres_dns}:5432/northwood_erp?currentSchema=${each.key},shared"
    "${upper(each.key)}_DB_USER" = "${each.key}_service"
  })

  secrets = {
    "${upper(each.key)}_DB_PASSWORD"    = module.secrets.service_db_password_arns[each.key]
    NORTHWOOD_SECURITY_DEMOBYPASS_TOKEN = module.secrets.demo_bypass_token_arn
  }
}

module "bffs" {
  source   = "../../modules/ecs-service"
  for_each = local.bffs

  name                          = each.key
  region                        = var.region
  cluster_arn                   = module.ecs_cluster.cluster_arn
  service_connect_namespace_arn = module.ecs_cluster.service_connect_namespace_arn
  execution_role_arn            = module.ecs_cluster.execution_role_arn
  task_role_arn                 = module.ecs_cluster.task_role_arn

  subnet_ids        = [module.network.subnet_ids.web]
  security_group_id = module.network.security_group_ids.web

  image          = "${module.ecr.repository_urls[each.key]}:${var.image_tag}"
  container_port = each.value.port
  cpu            = var.bff_cpu
  memory         = var.bff_memory
  desired_count  = lookup(var.bff_desired_counts, each.key, 1)
  advertise      = false # BFFs are Service Connect clients only; reached via ALB

  alb_target_group_arn = module.alb.target_group_arns[each.key]

  # Base env for both BFFs: kafka (event drawer) + the 7 Service Connect targets.
  # erp-bff additionally needs the Keycloak issuer for its OIDC login.
  environment = merge(
    local.telemetry_env,
    {
      SPRING_PROFILES_ACTIVE  = "kafka"
      KAFKA_BOOTSTRAP_SERVERS = local.kafka_bootstrap
    },
    local.bff_targets,
    each.key == "erp-web-ui-bff" ? { KEYCLOAK_ISSUER_URI = local.keycloak_issuer_internal } : {},
  )

  # demo-bff stamps the bypass header; erp-bff holds the Keycloak client secret.
  secrets = (
    each.key == "demo-web-ui-bff"
    ? { NORTHWOOD_SECURITY_DEMOBYPASS_TOKEN = module.secrets.demo_bypass_token_arn }
    : { KEYCLOAK_BFF_CLIENT_SECRET = module.secrets.bff_client_secret_arn }
  )

  # The target group must be listener-associated before the service registers,
  # and the doc deploys services before BFFs (§8).
  depends_on = [module.alb, module.services]
}
