# ===========================================================================
# Front-door static site on S3 — the always-on guest "start here" page.
#
# The welcome page is static HTML, so it doesn't need the EC2 fleet. Hosting it
# on S3 static-website hosting (HTTP, matching the demo's no-TLS posture) keeps
# the front door reachable even when the fleet is STOPPED for cost-saving — the
# page's built-in "Demo hours" notice then explains the app is asleep until the
# next weekday start. The web box still serves an identical copy on :80 when it
# is up; this S3 copy is what the front-door DNS name (dns.tf) points at, so the
# canonical entry URL survives a stop.
#
# Gated on var.front_door_domain (same as the DNS record). The bucket name MUST
# equal the domain — that is the requirement for a Route 53 alias to an S3
# website endpoint (see dns.tf). Nothing sensitive lives here: it is a separate,
# content-only, public-read bucket; the artifacts bucket stays private.
# ===========================================================================

locals {
  # Render the same template the web box uses, substituting the EIP-based ERP
  # origin. `$${ERP_URL}` is the literal token the on-box `sed` also replaces.
  front_door_html = var.front_door_domain != "" ? replace(
    file("${local.repo_root}/config/welcome.html.template"),
    "$${ERP_URL}",
    module.compute.erp_url,
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
