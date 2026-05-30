# ===========================================================================
# One ECR repository per deployable (the 7 services + 2 BFFs). The build
# script tags `mvn spring-boot:build-image` output as <repo_url>:<tag> and
# pushes here; the ecs-service module references the same repo URL.
# ===========================================================================

resource "aws_ecr_repository" "this" {
  for_each = toset(var.repo_names)

  name                 = "${var.name_prefix}/${each.key}"
  image_tag_mutability = "MUTABLE" # demo: reuse the same tag (e.g. "latest")

  image_scanning_configuration {
    scan_on_push = true
  }
}

# Keep the registry tidy: expire untagged images (old buildpack layers) after 14 days.
resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Expire untagged images after 14 days"
      selection = {
        tagStatus   = "untagged"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = 14
      }
      action = { type = "expire" }
    }]
  })
}
