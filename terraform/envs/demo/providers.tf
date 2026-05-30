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
