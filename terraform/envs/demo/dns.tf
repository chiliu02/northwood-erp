# ===========================================================================
# Front-door DNS.
#
# Points a friendly name at the web box's Elastic IP so the guest front door is
# reachable at http://<front_door_domain>/ instead of the raw IP. The front-door
# nginx is the default server on :80 and answers any Host header, so this needs
# NO web-box change.
#
# Deliberately scoped to the front door ONLY. The operational ERP UI (:8090),
# Keycloak (:8080) and the BFF (:8089) stay on the Elastic IP — the OIDC issuer
# and realm redirect URIs are pinned to the IP (var.keycloak_hostname), and the
# front door's "Enter the ERP" link still targets the IP. So this record is
# purely the entry-page hostname and touches neither the realm nor the UI.
# HTTP only (no TLS); pointing the UI/issuer at a domain would be a separate,
# larger change (Keycloak hostname + realm reimport + app reissue).
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
  ttl     = 300
  records = [module.compute.web_public_ip]
}
