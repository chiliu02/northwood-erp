# ===========================================================================
# One ECS Fargate service + task definition. Instantiated once per deployable
# via for_each in envs/demo/services.tf. docs/aws-demo-deployment.md §6.
# ===========================================================================

resource "aws_cloudwatch_log_group" "this" {
  name              = "/northwood/${var.name}"
  retention_in_days = var.log_retention_days
}

locals {
  # Port mapping name is referenced by the Service Connect `service` block.
  port_name = "app"
  sc_alias  = var.service_connect_alias != "" ? var.service_connect_alias : var.name
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name      = var.name
      image     = var.image
      essential = true

      portMappings = [
        {
          name          = local.port_name
          containerPort = var.container_port
          protocol      = "tcp"
          appProtocol   = "http"
        }
      ]

      environment = [for k, v in var.environment : { name = k, value = v }]
      secrets     = [for k, arn in var.secrets : { name = k, valueFrom = arn }]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this.name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = var.name
        }
      }
    }
  ])
}

resource "aws_ecs_service" "this" {
  name            = var.name
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = false # images pull via NAT / S3+ECR endpoints
  }

  service_connect_configuration {
    enabled   = true
    namespace = var.service_connect_namespace_arn

    # The 7 services advertise an alias (http://<name>:<port>); BFFs are
    # clients only (this block is omitted for them).
    dynamic "service" {
      for_each = var.advertise ? [1] : []
      content {
        port_name      = local.port_name
        discovery_name = local.sc_alias
        client_alias {
          port     = var.container_port
          dns_name = local.sc_alias
        }
      }
    }
  }

  # BFFs only: register tasks behind the public ALB.
  dynamic "load_balancer" {
    for_each = var.alb_target_group_arn != "" ? [1] : []
    content {
      target_group_arn = var.alb_target_group_arn
      container_name   = var.name
      container_port   = var.container_port
    }
  }

  # If you later attach Service Auto Scaling (target-tracking on CPU, §6),
  # add `lifecycle { ignore_changes = [desired_count] }` so TF stops fighting
  # the scaler. Until then, desired_count is managed here per the services map.
}
