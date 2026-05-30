# ===========================================================================
# Security groups — docs/aws-demo-deployment.md §4 (tier-to-tier, never 0.0.0.0/0
# except the ALB). Rules are standalone resources (not inline blocks) so that
# web-sg <-> obs-sg can reference each other without a dependency cycle.
#
# Fargate awsvpc tasks get their own ENI, so web-sg / app-sg attach to the
# *tasks*, not to EC2 — same rules either way.
# ===========================================================================

locals {
  sgs = {
    alb      = "ALB — internet-facing ingress"
    web      = "BFF tasks (demo-bff 8080, erp-bff 8089)"
    app      = "Service tasks (8081-8087) — internal only"
    postgres = "PostgreSQL EC2 (5432)"
    kafka    = "Kafka EC2 (9092)"
    keycloak = "Keycloak EC2 (8080)"
    obs      = "Observability EC2 (Grafana 3000 / OTLP+Loki ingest) — instance deferred, SG kept for §5.4"
  }
}

resource "aws_security_group" "this" {
  for_each    = local.sgs
  name        = "${var.name_prefix}-${each.key}-sg"
  description = each.value
  vpc_id      = aws_vpc.this.id
  tags        = { Name = "${var.name_prefix}-${each.key}-sg" }
}

# Allow-all egress for every SG (demo-grade; tighten per service in production).
resource "aws_vpc_security_group_egress_rule" "all" {
  for_each          = local.sgs
  security_group_id = aws_security_group.this[each.key].id
  description       = "All outbound"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# ---- alb-sg: 80 + 443 from the internet -----------------------------------
resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.this["alb"].id
  description       = "HTTP from internet (redirected to 443)"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.this["alb"].id
  description       = "HTTPS from internet"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

# ---- web-sg: BFF ports from ALB + obs scrape -------------------------------
resource "aws_vpc_security_group_ingress_rule" "web_from_alb" {
  for_each                     = toset([for p in var.bff_ports : tostring(p)])
  security_group_id            = aws_security_group.this["web"].id
  description                  = "BFF port ${each.key} from ALB"
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)
  referenced_security_group_id = aws_security_group.this["alb"].id
}

resource "aws_vpc_security_group_ingress_rule" "web_from_obs" {
  for_each                     = toset([for p in var.bff_ports : tostring(p)])
  security_group_id            = aws_security_group.this["web"].id
  description                  = "Prometheus scrape of BFF ${each.key} from obs"
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)
  referenced_security_group_id = aws_security_group.this["obs"].id
}

# ---- app-sg: 8081-8087 from web + obs scrape -------------------------------
resource "aws_vpc_security_group_ingress_rule" "app_from_web" {
  security_group_id            = aws_security_group.this["app"].id
  description                  = "Service ports from web tier (BFF -> service)"
  ip_protocol                  = "tcp"
  from_port                    = var.app_port_range.from
  to_port                      = var.app_port_range.to
  referenced_security_group_id = aws_security_group.this["web"].id
}

resource "aws_vpc_security_group_ingress_rule" "app_from_obs" {
  security_group_id            = aws_security_group.this["app"].id
  description                  = "Prometheus scrape of services from obs"
  ip_protocol                  = "tcp"
  from_port                    = var.app_port_range.from
  to_port                      = var.app_port_range.to
  referenced_security_group_id = aws_security_group.this["obs"].id
}

# ---- postgres-sg: 5432 from app --------------------------------------------
resource "aws_vpc_security_group_ingress_rule" "postgres_from_app" {
  security_group_id            = aws_security_group.this["postgres"].id
  description                  = "Postgres from service tasks"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.this["app"].id
}

# ---- kafka-sg: 9092 from app -----------------------------------------------
resource "aws_vpc_security_group_ingress_rule" "kafka_from_app" {
  security_group_id            = aws_security_group.this["kafka"].id
  description                  = "Kafka from service tasks"
  ip_protocol                  = "tcp"
  from_port                    = 9092
  to_port                      = 9092
  referenced_security_group_id = aws_security_group.this["app"].id
}

# ---- keycloak-sg: 8080 from app + web + alb --------------------------------
resource "aws_vpc_security_group_ingress_rule" "keycloak_from" {
  for_each                     = toset(["app", "web", "alb"])
  security_group_id            = aws_security_group.this["keycloak"].id
  description                  = "Keycloak 8080 from ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.this[each.key].id
}

# ---- obs-sg: Grafana 3000 from ALB; OTLP/Loki ingest from web+app ----------
# (Instance is out of the current scope; rules kept so §5.4 drops in cleanly.)
resource "aws_vpc_security_group_ingress_rule" "obs_grafana_from_alb" {
  security_group_id            = aws_security_group.this["obs"].id
  description                  = "Grafana from ALB"
  ip_protocol                  = "tcp"
  from_port                    = 3000
  to_port                      = 3000
  referenced_security_group_id = aws_security_group.this["alb"].id
}

resource "aws_vpc_security_group_ingress_rule" "obs_ingest_from" {
  # Discrete ports (not a range): 3100 Loki push, 4317 OTLP/gRPC, 4318 OTLP/HTTP.
  for_each = {
    for pair in setproduct(["web", "app"], [3100, 4317, 4318]) :
    "${pair[0]}-${pair[1]}" => { tier = pair[0], port = pair[1] }
  }
  security_group_id            = aws_security_group.this["obs"].id
  description                  = "Telemetry port ${each.value.port} from ${each.value.tier}"
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  referenced_security_group_id = aws_security_group.this[each.value.tier].id
}
