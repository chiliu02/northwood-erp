# ===========================================================================
# Network layer — docs/aws-deployment.html.
# Single VPC, single AZ. Three subnets: public (web/auth EC2 + NAT instance),
# app (services EC2, private), infra (data EC2, private).
#
# Egress for the private subnets goes through a small **NAT instance** (a
# t3.nano EC2 doing iptables masquerade) rather than a managed NAT Gateway —
# cheaper for a demo, and it keeps the design simple: the private boxes pull
# images straight from Docker Hub / quay.io / ECR and reach the public Keycloak
# issuer, so there's no ECR image-mirroring, no interface VPC endpoints, and no
# Keycloak JWKS/issuer split. The free S3 gateway endpoint still offloads S3 +
# ECR image-layer traffic off the NAT. See docs/aws-deployment.html "Egress".
# ===========================================================================

data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_region" "current" {}

data "aws_ssm_parameter" "al2023" {
  count = var.nat_ami_id == "" ? 1 : 0
  name  = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

locals {
  az         = var.az != "" ? var.az : data.aws_availability_zones.available.names[0]
  nat_ami_id = var.nat_ami_id != "" ? var.nat_ami_id : data.aws_ssm_parameter.al2023[0].value
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
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
# Internet gateway
# --------------------------------------------------------------------------

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = { Name = var.name_prefix }
}

# --------------------------------------------------------------------------
# NAT instance — private-subnet egress on the cheap (vs a managed NAT GW).
# source_dest_check must be off so it can forward traffic that isn't addressed
# to it; user-data turns on IP forwarding + iptables MASQUERADE.
# --------------------------------------------------------------------------

resource "aws_instance" "nat" {
  ami                         = local.nat_ami_id
  instance_type               = var.nat_instance_type
  subnet_id                   = aws_subnet.public.id
  associate_public_ip_address = true
  source_dest_check           = false
  vpc_security_group_ids      = [aws_security_group.this["nat"].id]

  user_data = <<-EOT
    #!/bin/bash
    set -euxo pipefail
    sysctl -w net.ipv4.ip_forward=1
    echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/99-nat.conf
    IFACE=$(ip route show default | awk '{print $5; exit}')
    iptables -t nat -A POSTROUTING -o "$IFACE" -j MASQUERADE
    iptables -A FORWARD -i "$IFACE" -m state --state RELATED,ESTABLISHED -j ACCEPT
    iptables -A FORWARD -o "$IFACE" -j ACCEPT
    dnf install -y iptables-services || true
    iptables-save > /etc/sysconfig/iptables || true
  EOT

  tags = { Name = "${var.name_prefix}-nat" }
}

# --------------------------------------------------------------------------
# Route tables: public -> IGW; private -> NAT instance.
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
    cidr_block           = "0.0.0.0/0"
    network_interface_id = aws_instance.nat.primary_network_interface_id
  }
  tags = { Name = "${var.name_prefix}-private" }
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "app" {
  subnet_id      = aws_subnet.app.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "infra" {
  subnet_id      = aws_subnet.infra.id
  route_table_id = aws_route_table.private.id
}

# Free S3 gateway endpoint — keeps ECR image-layer + artifact traffic off the NAT.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id, aws_route_table.public.id]
  tags              = { Name = "${var.name_prefix}-s3" }
}
