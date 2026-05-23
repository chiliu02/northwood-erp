# ===========================================================================
# Network layer — docs/aws-demo-deployment.md §4.
# Single VPC, single AZ. Four subnets: public (ALB+NAT), web (BFF tasks),
# app (service tasks), infra (Postgres/Kafka/Keycloak EC2s).
# ===========================================================================

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  az   = var.az != "" ? var.az : data.aws_availability_zones.available.names[0]
  az_b = var.az_b != "" ? var.az_b : data.aws_availability_zones.available.names[1]
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true # Kafka advertises private DNS — needs this on.
  tags                 = { Name = var.name_prefix }
}

# --------------------------------------------------------------------------
# Subnets
# --------------------------------------------------------------------------

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.subnet_cidrs.public
  availability_zone       = local.az
  map_public_ip_on_launch = true
  tags                    = { Name = "${var.name_prefix}-public", Tier = "public" }
}

# Second public subnet in az_b — exists only to satisfy the ALB's 2-AZ rule.
# No NAT, no instances; the demo workload stays in the primary AZ.
resource "aws_subnet" "public_b" {
  vpc_id                  = aws_vpc.this.id
  cidr_block              = var.subnet_cidrs.public_b
  availability_zone       = local.az_b
  map_public_ip_on_launch = true
  tags                    = { Name = "${var.name_prefix}-public-b", Tier = "public" }
}

resource "aws_subnet" "web" {
  vpc_id            = aws_vpc.this.id
  cidr_block        = var.subnet_cidrs.web
  availability_zone = local.az
  tags              = { Name = "${var.name_prefix}-web", Tier = "web" }
}

resource "aws_subnet" "app" {
  vpc_id            = aws_vpc.this.id
  cidr_block        = var.subnet_cidrs.app
  availability_zone = local.az
  tags              = { Name = "${var.name_prefix}-app", Tier = "app" }
}

resource "aws_subnet" "infra" {
  vpc_id            = aws_vpc.this.id
  cidr_block        = var.subnet_cidrs.infra
  availability_zone = local.az
  tags              = { Name = "${var.name_prefix}-infra", Tier = "infra" }
}

# --------------------------------------------------------------------------
# Internet gateway + NAT gateway
# Private subnets egress via NAT (Fargate pulls images from ECR, EC2 reaches
# SSM + pulls container images). The S3 gateway endpoint below offloads S3
# traffic (image layers, db/ staging) off NAT for free.
# --------------------------------------------------------------------------

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = var.name_prefix }
}

resource "aws_eip" "nat" {
  domain = "vpc"
  tags   = { Name = "${var.name_prefix}-nat" }
}

resource "aws_nat_gateway" "this" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public.id
  tags          = { Name = var.name_prefix }
  depends_on    = [aws_internet_gateway.this]
}

# --------------------------------------------------------------------------
# Route tables
# --------------------------------------------------------------------------

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }
  tags = { Name = "${var.name_prefix}-public" }
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id
  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.this.id
  }
  tags = { Name = "${var.name_prefix}-private" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "public_b" {
  subnet_id      = aws_subnet.public_b.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "web" {
  subnet_id      = aws_subnet.web.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "app" {
  subnet_id      = aws_subnet.app.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "infra" {
  subnet_id      = aws_subnet.infra.id
  route_table_id = aws_route_table.private.id
}

# Free S3 gateway endpoint — keeps ECR image-layer + db/ staging traffic off NAT.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]
  tags              = { Name = "${var.name_prefix}-s3" }
}

data "aws_region" "current" {}
