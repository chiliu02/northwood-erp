# ===========================================================================
# ECS Fargate cluster + Service Connect namespace + the two shared IAM roles
# every task uses. docs/aws-demo-deployment.md §6.
# ===========================================================================

resource "aws_ecs_cluster" "this" {
  name = var.name_prefix

  setting {
    name  = "containerInsights"
    value = "disabled" # demo: keep CloudWatch cost down; flip to "enabled" if needed
  }
}

# Service Connect uses a Cloud Map HTTP namespace. Each service advertises its
# port under this namespace; BFFs call http://<service>:<port> and ECS
# load-balances across that service's tasks (no internal ALB).
resource "aws_service_discovery_http_namespace" "this" {
  name        = var.service_connect_namespace
  description = "Northwood Service Connect namespace"
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE"]
  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

# --------------------------------------------------------------------------
# Task execution role — pulls ECR images, writes logs, reads the task secrets.
# --------------------------------------------------------------------------

data "aws_iam_policy_document" "assume_ecs_tasks" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name_prefix}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.assume_ecs_tasks.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Allow reading exactly the task secrets (DB passwords, BFF client secret,
# bypass token) — scoped to the ARNs passed in, not secretsmanager:*.
data "aws_iam_policy_document" "read_secrets" {
  count = length(var.task_secret_arns) > 0 ? 1 : 0
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = var.task_secret_arns
  }
}

resource "aws_iam_role_policy" "read_secrets" {
  count  = length(var.task_secret_arns) > 0 ? 1 : 0
  name   = "read-task-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.read_secrets[0].json
}

# --------------------------------------------------------------------------
# Task role — the app itself makes no AWS API calls, so this is empty (but a
# distinct role keeps task-vs-execution permissions cleanly separated).
# --------------------------------------------------------------------------

resource "aws_iam_role" "task" {
  name               = "${var.name_prefix}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.assume_ecs_tasks.json
}
