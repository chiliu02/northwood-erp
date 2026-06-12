# ===========================================================================
# Cost-saver schedule — auto-start/stop the EC2 fleet on a weekday window.
#
# Two EventBridge Scheduler schedules call the EC2 API directly via the
# "universal target" (arn:aws:scheduler:::aws-sdk:ec2:{start,stop}Instances) —
# no Lambda. Default window: START 08:30, STOP 17:00, Mon-Fri, Australia/Sydney
# (var.start_cron / var.stop_cron / var.scheduler_timezone). MON-FRI means the
# fleet stays stopped all weekend (last stop Fri 17:00, next start Mon 08:30).
#
# This is the managed twin of terraform/ops/{start,stop}.ps1: same four boxes
# (web + app + data + NAT), same stop-don't-destroy semantics, so containers
# come back via their `--restart unless-stopped` policy with no DB re-seed.
#
# Toggle off with -var enable_scheduler=false (leaves the instances running).
# ===========================================================================

locals {
  # The four cost-bearing instances: the three app boxes + the NAT box.
  scheduled_instance_ids = concat(
    values(module.compute.instance_ids),
    [module.network.nat_instance_id],
  )
}

# --- IAM: a role EventBridge Scheduler assumes to call EC2 start/stop ---------

data "aws_iam_policy_document" "scheduler_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
    # Lock the role to this account's scheduler (confused-deputy guard).
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

data "aws_iam_policy_document" "scheduler_ec2" {
  statement {
    sid       = "StartStopFleet"
    actions   = ["ec2:StartInstances", "ec2:StopInstances"]
    resources = ["arn:aws:ec2:${var.region}:${data.aws_caller_identity.current.account_id}:instance/*"]
    # Scope to this deployment's instances by Name tag, so the role can't be
    # used to start/stop unrelated instances even though the ARN is instance/*.
    condition {
      test     = "StringLike"
      variable = "ec2:ResourceTag/Name"
      values   = ["${var.name_prefix}-*"]
    }
  }
}

resource "aws_iam_role" "scheduler" {
  count              = var.enable_scheduler ? 1 : 0
  name               = "${var.name_prefix}-scheduler"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume.json
}

resource "aws_iam_role_policy" "scheduler" {
  count  = var.enable_scheduler ? 1 : 0
  name   = "ec2-start-stop"
  role   = aws_iam_role.scheduler[0].id
  policy = data.aws_iam_policy_document.scheduler_ec2.json
}

# --- Schedules ---------------------------------------------------------------

resource "aws_scheduler_schedule" "start" {
  count = var.enable_scheduler ? 1 : 0
  name  = "${var.name_prefix}-start"

  flexible_time_window {
    mode = "OFF"
  }

  schedule_expression          = var.start_cron
  schedule_expression_timezone = var.scheduler_timezone

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ec2:startInstances"
    role_arn = aws_iam_role.scheduler[0].arn
    input    = jsonencode({ InstanceIds = local.scheduled_instance_ids })
  }

  depends_on = [aws_iam_role_policy.scheduler]
}

resource "aws_scheduler_schedule" "stop" {
  count = var.enable_scheduler ? 1 : 0
  name  = "${var.name_prefix}-stop"

  flexible_time_window {
    mode = "OFF"
  }

  schedule_expression          = var.stop_cron
  schedule_expression_timezone = var.scheduler_timezone

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ec2:stopInstances"
    role_arn = aws_iam_role.scheduler[0].arn
    input    = jsonencode({ InstanceIds = local.scheduled_instance_ids })
  }

  depends_on = [aws_iam_role_policy.scheduler]
}
