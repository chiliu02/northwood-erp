# ===========================================================================
# Public DNS for the three HTTPS surfaces (all sub-domains of dns_zone_name).
#
#   front_door  (welcome page) : ALIAS -> the always-on CloudFront distribution
#                                (frontdoor.tf), so it stays reachable over HTTPS
#                                even when the EC2 fleet is stopped for cost-saving.
#   ui_hostname (ERP SPA)      : A  -> the web box Elastic IP. Caddy on the box
#   auth_hostname (Keycloak)   : A  -> the web box Elastic IP. terminates TLS
#                                (Let's Encrypt) for both and proxies the
#                                loopback-bound SPA nginx / Keycloak.
#
# The ui/auth records point at the stable Elastic IP, so they (and the certs
# Caddy issues for them) survive instance replacement + stop/start. The OIDC
# issuer + realm redirect URIs are pinned to auth_hostname / ui_hostname — the
# whole browser auth flow runs over HTTPS end to end.
# ===========================================================================

data "aws_route53_zone" "primary" {
  name = var.dns_zone_name
}

# Front door — ALIAS to CloudFront. An alias (not CNAME) is required because the
# record can sit at any name and resolves to CloudFront's A/AAAA set directly.
resource "aws_route53_record" "front_door" {
  count   = var.front_door_domain != "" ? 1 : 0
  zone_id = data.aws_route53_zone.primary.zone_id
  name    = var.front_door_domain
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.front_door[0].domain_name
    zone_id                = aws_cloudfront_distribution.front_door[0].hosted_zone_id
    evaluate_target_health = false
  }
}

# Operational ERP SPA + Keycloak — A records to the web box Elastic IP.
resource "aws_route53_record" "ui" {
  zone_id = data.aws_route53_zone.primary.zone_id
  name    = var.ui_hostname
  type    = "A"
  ttl     = 300
  records = [module.compute.web_public_ip]
}

resource "aws_route53_record" "auth" {
  zone_id = data.aws_route53_zone.primary.zone_id
  name    = var.auth_hostname
  type    = "A"
  ttl     = 300
  records = [module.compute.web_public_ip]
}

# Public, read-only Grafana — Caddy on the web box proxies it to the data box's
# :3000. Only when observability is on and a hostname is set.
resource "aws_route53_record" "grafana" {
  count   = var.enable_observability && var.grafana_hostname != "" ? 1 : 0
  zone_id = data.aws_route53_zone.primary.zone_id
  name    = var.grafana_hostname
  type    = "A"
  ttl     = 300
  records = [module.compute.web_public_ip]
}
