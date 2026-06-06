# ===========================================================================
# Security groups — docs/aws-deployment.html (strictly tier-to-tier; only the
# web SG faces 0.0.0.0/0). Standalone rule resources so SGs can reference each
# other without inline-block dependency cycles.
#
#   web   : public EC2 (front door :80 + erp-bff :8089 + Keycloak :8080) — internet-facing
#   app   : services EC2 (8081-8087) — from web only
#   infra : data EC2 (Postgres 5432 / Kafka 9092 / telemetry) — from app/web
#   nat   : NAT instance — forwards egress for the private subnets
# ===========================================================================

locals {
  sgs = {
    web   = "Public EC2 - front door 80 + erp-bff 8089 + Keycloak 8080 (internet-facing)"
    app   = "Services EC2 - 8081-8087, private"
    infra = "Data EC2 - Postgres 5432 / Kafka 9092 / observability, private"
    nat   = "NAT instance - egress for the private subnets"
  }
}

resource "aws_security_group" "this" {
  for_each    = local.sgs
  name        = "${var.name_prefix}-${each.key}-sg"
  description = each.value
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name_prefix}-${each.key}-sg" }
}

resource "aws_vpc_security_group_egress_rule" "all" {
  for_each          = local.sgs
  security_group_id = aws_security_group.this[each.key].id
  description       = "All outbound"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# ---- web-sg: front door + erp-bff + Keycloak from the internet ------------
resource "aws_vpc_security_group_ingress_rule" "web_front_door" {
  security_group_id = aws_security_group.this["web"].id
  description       = "Guest front door (static welcome page) from internet"
  ip_protocol       = "tcp"
  from_port         = var.welcome_port
  to_port           = var.welcome_port
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "web_bff" {
  security_group_id = aws_security_group.this["web"].id
  description       = "erp-web-ui-bff from internet"
  ip_protocol       = "tcp"
  from_port         = var.bff_port
  to_port           = var.bff_port
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "web_keycloak_internet" {
  security_group_id = aws_security_group.this["web"].id
  description       = "Keycloak from internet (browser OIDC redirect)"
  ip_protocol       = "tcp"
  from_port         = var.keycloak_port
  to_port           = var.keycloak_port
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "web_ui" {
  security_group_id = aws_security_group.this["web"].id
  description       = "Operational ERP SPA (nginx) from internet"
  ip_protocol       = "tcp"
  from_port         = var.ui_port
  to_port           = var.ui_port
  cidr_ipv4         = "0.0.0.0/0"
}

# ---- app-sg: service ports from the web tier (BFF -> services) ------------
resource "aws_vpc_security_group_ingress_rule" "app_from_web" {
  security_group_id            = aws_security_group.this["app"].id
  description                  = "Service ports from web tier (BFF to service)"
  ip_protocol                  = "tcp"
  from_port                    = var.app_port_range.from
  to_port                      = var.app_port_range.to
  referenced_security_group_id = aws_security_group.this["web"].id
}

# ---- infra-sg: Postgres + Kafka from app; telemetry from app + web --------
resource "aws_vpc_security_group_ingress_rule" "infra_postgres_from_app" {
  security_group_id            = aws_security_group.this["infra"].id
  description                  = "Postgres from services"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.this["app"].id
}

resource "aws_vpc_security_group_ingress_rule" "infra_kafka_from_app" {
  security_group_id            = aws_security_group.this["infra"].id
  description                  = "Kafka from services"
  ip_protocol                  = "tcp"
  from_port                    = 9092
  to_port                      = 9092
  referenced_security_group_id = aws_security_group.this["app"].id
}

resource "aws_vpc_security_group_ingress_rule" "infra_telemetry" {
  for_each = {
    for pair in setproduct(["app", "web"], var.telemetry_ports) :
    "${pair[0]}-${pair[1]}" => { tier = pair[0], port = pair[1] }
  }
  security_group_id            = aws_security_group.this["infra"].id
  description                  = "Telemetry port ${each.value.port} from ${each.value.tier}"
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  referenced_security_group_id = aws_security_group.this[each.value.tier].id
}

# ---- nat-sg: accept everything from inside the VPC (it just forwards) ------
resource "aws_vpc_security_group_ingress_rule" "nat_from_vpc" {
  security_group_id = aws_security_group.this["nat"].id
  description       = "All traffic from the VPC (NAT forwards private egress)"
  ip_protocol       = "-1"
  cidr_ipv4         = var.vpc_cidr
}
