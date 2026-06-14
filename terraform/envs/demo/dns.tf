# ===========================================================================
# Front-door DNS.
#
# Aliases the friendly name at the ALWAYS-ON S3 front-door site (frontdoor.tf),
# not the web box — so http://<front_door_domain>/ stays reachable even when the
# fleet is stopped for cost-saving (the page's "Demo hours" notice explains the
# app is asleep). An A alias to an S3 website endpoint requires the bucket name
# to equal the record name, which frontdoor.tf guarantees.
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
  type    = "A"

  alias {
    name                   = aws_s3_bucket_website_configuration.front_door[0].website_endpoint
    zone_id                = aws_s3_bucket.front_door[0].hosted_zone_id
    evaluate_target_health = false
  }
}
