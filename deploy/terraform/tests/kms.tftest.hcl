# ADR-023: two KMS keys. Only the secrets key carries the tag that lets the
# platform External Secrets role decrypt; the database key (storage, snapshots,
# Performance Insights, the RDS-managed master secret) stays untagged, so
# var.tags may not add that tag to it through local.tags.
# Offline: the AWS provider is mocked; each key gets its own ARN and key id.

mock_provider "aws" {
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111122223333"
    }
  }
  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }
}

override_resource {
  target = aws_kms_key.database
  values = {
    arn    = "arn:aws:kms:me-central-1:111122223333:key/00000000-0000-4000-8000-0000000000db"
    key_id = "00000000-0000-4000-8000-0000000000db"
  }
}

override_resource {
  target = aws_kms_key.secrets
  values = {
    arn    = "arn:aws:kms:me-central-1:111122223333:key/00000000-0000-4000-8000-00000000005e"
    key_id = "00000000-0000-4000-8000-00000000005e"
  }
}

# The RDS-managed master secret (computed; outputs.tf reads its ARN).
override_resource {
  target = aws_rds_cluster.database
  values = {
    master_user_secret = [{
      kms_key_id    = "00000000-0000-4000-8000-0000000000db"
      secret_arn    = "arn:aws:secretsmanager:me-central-1:111122223333:secret:rds!cluster-mock"
      secret_status = "active"
    }]
  }
}

variables {
  aws_region                 = "me-central-1"
  environment                = "dev"
  vpc_id                     = "vpc-0123456789abcdef0"
  private_subnet_ids         = ["subnet-0123456789abcdef0", "subnet-0fedcba9876543210"]
  workload_security_group_id = "sg-0123456789abcdef0"
  eks_oidc_provider_arn      = "arn:aws:iam::111122223333:oidc-provider/oidc.eks.me-central-1.amazonaws.com/id/EXAMPLE"
  eks_oidc_provider_url      = "oidc.eks.me-central-1.amazonaws.com/id/EXAMPLE"
  identity_provider_url      = "https://identity.dev.example.internal/realms/fintechbankx"
  observability_endpoint     = "https://otel.dev.example.internal"
  msk_cluster_arn            = "arn:aws:kafka:me-central-1:111122223333:cluster/fintechbankx-dev/00000000-0000-0000-0000-000000000000-1"
  tags                       = { "cost-centre" = "compliance" }
}

run "secrets_and_database_keys_are_split" {
  command = apply

  assert {
    condition     = aws_kms_key.database.arn != aws_kms_key.secrets.arn
    error_message = "the database and secrets keys must be distinct keys"
  }

  assert {
    condition     = aws_kms_key.secrets.tags["fintechbankx.io/secrets"] == "true"
    error_message = "the secrets key carries fintechbankx.io/secrets=true (External Secrets may decrypt it)"
  }

  assert {
    condition     = !contains([for k in keys(aws_kms_key.database.tags) : lower(k)], "fintechbankx.io/secrets")
    error_message = "the database key must not carry fintechbankx.io/secrets"
  }

  assert {
    condition     = aws_kms_key.database.tags["cost-centre"] == "compliance" && aws_kms_key.secrets.tags["cost-centre"] == "compliance"
    error_message = "var.tags still reach both keys"
  }

  assert {
    condition     = aws_kms_key.database.enable_key_rotation && aws_kms_key.secrets.enable_key_rotation
    error_message = "both keys rotate"
  }

  assert {
    condition     = aws_secretsmanager_secret.app_database.kms_key_id == "arn:aws:kms:me-central-1:111122223333:key/00000000-0000-4000-8000-00000000005e"
    error_message = "the db-app secret is encrypted with the secrets key"
  }

  assert {
    condition     = aws_secretsmanager_secret.migration_database.kms_key_id == "arn:aws:kms:me-central-1:111122223333:key/00000000-0000-4000-8000-00000000005e"
    error_message = "the db-migration secret is encrypted with the secrets key"
  }

  assert {
    condition     = aws_rds_cluster.database.master_user_secret_kms_key_id == "00000000-0000-4000-8000-0000000000db"
    error_message = "the RDS-managed master secret is encrypted with the database key"
  }

  assert {
    condition     = aws_rds_cluster.database.kms_key_id == "arn:aws:kms:me-central-1:111122223333:key/00000000-0000-4000-8000-0000000000db"
    error_message = "Aurora storage is encrypted with the database key"
  }
}

run "tags_may_not_carry_the_external_secrets_tag" {
  command = plan

  variables {
    tags = { "fintechbankx.io/secrets" = "true" }
  }

  expect_failures = [var.tags]
}

run "tags_may_not_carry_the_external_secrets_tag_in_another_case" {
  command = plan

  variables {
    tags = { "FintechBankX.io/Secrets" = "true" }
  }

  expect_failures = [var.tags]
}
