#!/usr/bin/env python3
"""Reads a rendered chart on stdin and checks that the Deployment's pod
template carries fintechbankx.io/service-id=<expected>. Platform outbox
alerts select on the service_id label scraped from this pod label.

Usage: helm template ... | check-service-id-label.py svc-cmp-evidence
"""
import sys

import yaml

expected = sys.argv[1]
deployments = [d for d in yaml.safe_load_all(sys.stdin) if d and d.get("kind") == "Deployment"]
if len(deployments) != 1:
    sys.exit(f"expected one Deployment, found {len(deployments)}")
labels = deployments[0]["spec"]["template"]["metadata"].get("labels") or {}
actual = labels.get("fintechbankx.io/service-id")
if actual != expected:
    sys.exit(f"pod label fintechbankx.io/service-id is {actual!r}, not {expected!r}")
print(f"pod label fintechbankx.io/service-id={expected}")
