# ===========================================================================
# Security groups — docs/aws-deployment.html (strictly tier-to-tier; only the
# web SG faces 0.0.0.0/0). Standalone rule resources so SGs can reference each
# other without inline-block dependency cycles.
#
#   web   : public EC2 (Caddy TLS edge :443 + :80 ACME/redirect) — internet-facing
#   app   : services EC2 (8081-8087) — from web only
#   infra : data EC2 (Postgres 5432 / Kafka 9092 / telemetry) — from app/web
#   nat   : NAT instance — forwards egress for the private subnets
# ===========================================================================

locals {
  sgs = {
    web   = "Public EC2 - Caddy TLS edge :443 + :80 ACME/redirect (internet-facing)"
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

# ---- web-sg: Caddy TLS edge from the internet -----------------------------
# Caddy is the only public entry. It terminates TLS for the ERP UI + Keycloak
# hostnames on :443 and serves the Let's Encrypt HTTP-01 challenge + http->https
# redirect on :80. The BFF (:8089), Keycloak (:8080) and SPA nginx (:8090) sit
# behind it (loopback-bound) and are NOT internet-exposed.
resource "aws_vpc_security_group_ingress_rule" "web_https" {
  security_group_id = aws_security_group.this["web"].id
  description       = "HTTPS (Caddy TLS edge) from internet"
  ip_protocol       = "tcp"
  from_port         = var.https_port
  to_port           = var.https_port
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "web_http" {
  security_group_id = aws_security_group.this["web"].id
  description       = "HTTP (Caddy ACME HTTP-01 challenge + redirect to HTTPS) from internet"
  ip_protocol       = "tcp"
  from_port         = var.welcome_port
  to_port           = var.welcome_port
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

# Prometheus on the data box scrapes each service's /actuator/prometheus over
# the service port range. (The BFF on the web box is already internet-open.)
resource "aws_vpc_security_group_ingress_rule" "app_from_infra" {
  security_group_id            = aws_security_group.this["app"].id
  description                  = "Service ports from infra tier (Prometheus scrape)"
  ip_protocol                  = "tcp"
  from_port                    = var.app_port_range.from
  to_port                      = var.app_port_range.to
  referenced_security_group_id = aws_security_group.this["infra"].id
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
