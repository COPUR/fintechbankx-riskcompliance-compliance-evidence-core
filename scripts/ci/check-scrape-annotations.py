#!/usr/bin/env python3
"""Reads a rendered chart on stdin and checks the Deployment's pod template
carries the Prometheus scrape annotations: scrape "true", port = the
container's management port, path /actuator/prometheus."""
import sys

import yaml

deployments = [d for d in yaml.safe_load_all(sys.stdin) if d and d.get("kind") == "Deployment"]
if len(deployments) != 1:
    sys.exit(f"expected one Deployment, found {len(deployments)}")
pod = deployments[0]["spec"]["template"]
annotations = pod["metadata"].get("annotations") or {}
ports = {p["name"]: str(p["containerPort"]) for c in pod["spec"]["containers"] for p in c.get("ports", [])}
expected = {
    "prometheus.io/scrape": "true",
    "prometheus.io/port": ports.get("management"),
    "prometheus.io/path": "/actuator/prometheus",
}
wrong = {k: annotations.get(k) for k, v in expected.items() if v is None or str(annotations.get(k)) != v}
if wrong:
    sys.exit(f"pod scrape annotations missing or wrong: {wrong} (expected {expected})")
print("pod scrape annotations present:", ", ".join(f"{k}={v}" for k, v in expected.items()))
