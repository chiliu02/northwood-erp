# ===========================================================================
# Front-door DNS.
#
# Points the friendly name at the ALWAYS-ON S3 front-door site (frontdoor.tf),
# not the web box — so http://<front_door_domain>/ stays reachable even when the
# fleet is stopped for cost-saving (the page's "Demo hours" notice explains the
# app is asleep).
#
# CNAME, not an A-alias. An A-alias to the S3 website endpoint is the "textbook"
# approach, but in practice Route 53 returned NODATA for it here (the alias never
# evaluated to an address — every authoritative NS answered the record with an
# empty A set, while serving the rest of the zone fine). A CNAME to the S3 website
# endpoint resolves through ordinary DNS (endpoint -> regional S3 A records), which
# works reliably; it's legal because front_door_domain is a sub-domain, not the
# zone apex. The HTTP Host header stays <front_door_domain>, so S3 still matches
# the bucket (bucket name == domain, per frontdoor.tf).
#
# Deliberately scoped to the front door ONLY. The operational ERP UI (:8090),
# Keycloak (:8080) and the BFF (:8089) stay on the Elastic IP — the OIDC issuer
# and realm redirect URIs are pinned to the IP (var.keycloak_hostname), and the
# front door's "Enter the ERP" link still targets the IP. So this record is
# purely the entry-page hostname and touches neither the realm nor the UI.
# HTTP only (no TLS, S3 website endpoints are HTTP); pointing the UI/issuer at a
# domain would be a separate, larger change (Keycloak hostname + realm reimport
# + app reissue).
# ===========================================================================

data "aws_route53_zone" "primary" {
  count = var.front_door_domain != "" ? 1 : 0
  name  = var.dns_zone_name
}

resource "aws_route53_record" "front_door" {
  count   = var.front_door_domain != "" ? 1 : 0
  zone_id = data.aws_route53_zone.primary[0].zone_id
  name    = var.front_door_domain
  type    = "CNAME"
  ttl     = 300
  records = [aws_s3_bucket_website_configuration.front_door[0].website_endpoint]
}
