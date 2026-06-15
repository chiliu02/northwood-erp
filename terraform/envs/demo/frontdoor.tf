# ===========================================================================
# Front-door static site — the always-on guest "start here" page, served over
# HTTPS by CloudFront in front of an S3 static-website bucket.
#
# The welcome page is static HTML, so it doesn't need the EC2 fleet. Hosting it
# on S3 keeps the front door reachable even when the fleet is STOPPED for
# cost-saving — the page's built-in "Demo hours" notice then explains the app is
# asleep until the next weekday start. CloudFront sits in front to add TLS (S3
# website endpoints are HTTP-only) using an ACM cert for front_door_domain, and
# the Route 53 alias (dns.tf) points at the distribution.
#
# Gated on var.front_door_domain. Nothing sensitive lives here: it is a separate,
# content-only, public-read bucket; the artifacts bucket stays private. The S3
# website endpoint is used as a CloudFront custom origin (http-only to S3); the
# bucket name no longer needs to equal the domain (CloudFront, not a direct S3
# alias, is what the DNS name resolves to).
# ===========================================================================

locals {
  # Render the same template the web box uses, substituting the EIP-based ERP
  # origin and the public read-only Grafana URL. `$${ERP_URL}` / `$${GRAFANA_URL}`
  # are the literal tokens the on-box `envsubst` also replaces. When Grafana is
  # not published (grafana_url empty), fall back to "#" so the link is inert
  # rather than leaking the unreplaced token.
  front_door_html = var.front_door_domain != "" ? replace(
    replace(
      file("${local.repo_root}/config/welcome.html.template"),
      "$${ERP_URL}",
      module.compute.erp_url,
    ),
    "$${GRAFANA_URL}",
    module.compute.grafana_url != "" ? module.compute.grafana_url : "#",
  ) : ""
}

resource "aws_s3_bucket" "front_door" {
  count  = var.front_door_domain != "" ? 1 : 0
  bucket = var.front_door_domain
}

resource "aws_s3_bucket_website_configuration" "front_door" {
  count  = var.front_door_domain != "" ? 1 : 0
  bucket = aws_s3_bucket.front_door[0].id

  index_document {
    suffix = "index.html"
  }
  # Any unknown path falls back to the welcome page rather than an S3 XML error.
  error_document {
    key = "index.html"
  }
}

resource "aws_s3_bucket_public_access_block" "front_door" {
  count                   = var.front_door_domain != "" ? 1 : 0
  bucket                  = aws_s3_bucket.front_door[0].id
  block_public_acls       = false
  block_public_policy     = false
  ignore_public_acls      = false
  restrict_public_buckets = false
}

data "aws_iam_policy_document" "front_door_public_read" {
  count = var.front_door_domain != "" ? 1 : 0
  statement {
    sid       = "PublicReadGetObject"
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.front_door[0].arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }
}

resource "aws_s3_bucket_policy" "front_door" {
  count  = var.front_door_domain != "" ? 1 : 0
  bucket = aws_s3_bucket.front_door[0].id
  policy = data.aws_iam_policy_document.front_door_public_read[0].json
  # The policy is public, so it can only be attached after the access block stops
  # rejecting public policies.
  depends_on = [aws_s3_bucket_public_access_block.front_door]
}

resource "aws_s3_object" "front_door_index" {
  count        = var.front_door_domain != "" ? 1 : 0
  bucket       = aws_s3_bucket.front_door[0].id
  key          = "index.html"
  content      = local.front_door_html
  content_type = "text/html"
  etag         = md5(local.front_door_html)
}

# ---------------------------------------------------------------------------
# TLS: ACM certificate (MUST be in us-east-1 for CloudFront) + DNS validation.
# ---------------------------------------------------------------------------

resource "aws_acm_certificate" "front_door" {
  count             = var.front_door_domain != "" ? 1 : 0
  provider          = aws.us_east_1
  domain_name       = var.front_door_domain
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "front_door_cert_validation" {
  for_each = var.front_door_domain != "" ? {
    for dvo in aws_acm_certificate.front_door[0].domain_validation_options :
    dvo.domain_name => {
      name   = dvo.resource_record_name
      type   = dvo.resource_record_type
      record = dvo.resource_record_value
    }
  } : {}

  zone_id         = data.aws_route53_zone.primary.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "front_door" {
  count                   = var.front_door_domain != "" ? 1 : 0
  provider                = aws.us_east_1
  certificate_arn         = aws_acm_certificate.front_door[0].arn
  validation_record_fqdns = [for r in aws_route53_record.front_door_cert_validation : r.fqdn]
}

# ---------------------------------------------------------------------------
# CloudFront — TLS edge in front of the S3 website endpoint (custom origin).
# ---------------------------------------------------------------------------

resource "aws_cloudfront_distribution" "front_door" {
  count               = var.front_door_domain != "" ? 1 : 0
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "${var.name_prefix} guest front door"
  default_root_object = "index.html"
  aliases             = [var.front_door_domain]
  # PriceClass_100 (NA + EU edges) is the cheapest; low-traffic demo traffic from
  # elsewhere is still served, just from a farther edge. Bump if latency matters.
  price_class = "PriceClass_100"

  origin {
    origin_id   = "s3-website"
    domain_name = aws_s3_bucket_website_configuration.front_door[0].website_endpoint

    # S3 website endpoints only speak HTTP — CloudFront must reach them as a
    # custom origin over http-only (it still serves viewers over HTTPS).
    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id       = "s3-website"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    forwarded_values {
      query_string = false
      cookies {
        forward = "none"
      }
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.front_door[0].certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}
