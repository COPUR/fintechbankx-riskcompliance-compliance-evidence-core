#!/usr/bin/env python3
"""Reads a rendered chart on stdin and checks the platform component label
(cicd-templates 335a345): app pods carry app.kubernetes.io/component=service,
and the Deployment, Service, PodDisruptionBudget and topology-spread
selectors include it. The HPA targets the Deployment by name."""
import sys

import yaml

KEY, VALUE = "app.kubernetes.io/component", "service"
docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
by_kind = {}
for d in docs:
    by_kind.setdefault(d["kind"], []).append(d)

problems = []


def require(where, labels):
    if (labels or {}).get(KEY) != VALUE:
        problems.append(f"{where}: {KEY}={VALUE} missing (has {(labels or {}).get(KEY)!r})")


for kind in ("Deployment", "Service", "PodDisruptionBudget"):
    if len(by_kind.get(kind, [])) != 1:
        problems.append(f"expected one {kind}, found {len(by_kind.get(kind, []))}")
if not problems:
    deployment = by_kind["Deployment"][0]
    pod = deployment["spec"]["template"]
    require("pod template labels", pod["metadata"].get("labels"))
    require("Deployment selector", deployment["spec"]["selector"].get("matchLabels"))
    for i, constraint in enumerate(pod["spec"].get("topologySpreadConstraints", [])):
        require(f"topologySpreadConstraints[{i}] selector", constraint["labelSelector"].get("matchLabels"))
    require("Service selector", by_kind["Service"][0]["spec"].get("selector"))
    require("PodDisruptionBudget selector", by_kind["PodDisruptionBudget"][0]["spec"]["selector"].get("matchLabels"))

if problems:
    sys.exit("component label check failed:\n  " + "\n  ".join(problems))
print(f"{KEY}={VALUE} on the pods and in the Deployment, Service, PDB and topology-spread selectors")
