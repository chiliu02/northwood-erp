# ===========================================================================
# Public ALB — docs/aws-demo-deployment.md §7. Internet-facing, in the two
# public subnets. Target groups are `ip` type (Fargate awsvpc tasks). One
# target is the listener default; others are matched by host/path rules.
# ===========================================================================

locals {
  default_target  = one([for k, t in var.targets : k if t.default])
  rule_targets    = { for k, t in var.targets : k => t if !t.default }
  https_enabled   = var.certificate_arn != ""
  primary_port    = local.https_enabled ? 443 : 80
}

resource "aws_lb" "this" {
  name               = var.name_prefix
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.security_group_id]
  subnets            = var.subnet_ids
}

resource "aws_lb_target_group" "this" {
  for_each = var.targets

  name                 = "${var.name_prefix}-${each.key}"
  port                 = each.value.port
  protocol             = "HTTP"
  vpc_id               = var.vpc_id
  target_type          = "ip"
  deregistration_delay = 10

  health_check {
    path                = each.value.health_check_path
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
    timeout             = 5
    matcher             = "200"
  }
}

# ---- HTTP-only path: single :80 listener, default forwards to the default TG -
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = local.https_enabled ? "redirect" : "forward"

    # When HTTPS is on, :80 just redirects to :443.
    dynamic "redirect" {
      for_each = local.https_enabled ? [1] : []
      content {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }

    target_group_arn = local.https_enabled ? null : aws_lb_target_group.this[local.default_target].arn
  }
}

# ---- HTTPS listener (only when a cert is supplied) -------------------------
resource "aws_lb_listener" "https" {
  count             = local.https_enabled ? 1 : 0
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[local.default_target].arn
  }
}

# ---- rules for the non-default target(s) -----------------------------------
resource "aws_lb_listener_rule" "this" {
  for_each = local.rule_targets

  listener_arn = local.https_enabled ? aws_lb_listener.https[0].arn : aws_lb_listener.http.arn
  priority     = each.value.priority

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[each.key].arn
  }

  dynamic "condition" {
    for_each = each.value.host_headers != null ? [1] : []
    content {
      host_header {
        values = each.value.host_headers
      }
    }
  }

  dynamic "condition" {
    for_each = each.value.path_patterns != null ? [1] : []
    content {
      path_pattern {
        values = each.value.path_patterns
      }
    }
  }
}
