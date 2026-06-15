provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "northwood"
      Env       = "demo"
      ManagedBy = "terraform"
    }
  }
}

# CloudFront viewer certificates must live in us-east-1, regardless of where the
# rest of the stack runs. This aliased provider exists solely so the front-door
# ACM cert (frontdoor.tf) can be issued there; nothing else uses it.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project   = "northwood"
      Env       = "demo"
      ManagedBy = "terraform"
    }
  }
}
