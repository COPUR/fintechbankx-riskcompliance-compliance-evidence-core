#!/usr/bin/env python3
"""Checks every ExternalSecret in a rendered chart (stdin) against the platform
secrets contract: metadata.labels app.kubernetes.io/name equals the chart's
service account name (argv[1]), and every remoteRef.key is
"<env>/<service account>/<name>", the only path the External Secrets role may read."""
import re
import sys

import yaml

service_account = sys.argv[1]
key_pattern = re.compile(r"^[a-z0-9][a-z0-9-]*/" + re.escape(service_account) + r"/[A-Za-z0-9._/-]+$")
failures = []
found = 0
for doc in yaml.safe_load_all(sys.stdin):
    if not doc or doc.get("kind") != "ExternalSecret":
        continue
    found += 1
    name = doc.get("metadata", {}).get("name", "?")
    label = (doc.get("metadata", {}).get("labels") or {}).get("app.kubernetes.io/name")
    if label != service_account:
        failures.append(f"ExternalSecret {name}: app.kubernetes.io/name is {label!r}, expected {service_account!r}")
    refs = [d.get("remoteRef", {}) for d in doc.get("spec", {}).get("data", []) or []]
    refs += [d.get("extract", {}) for d in doc.get("spec", {}).get("dataFrom", []) or []]
    for ref in refs:
        key = ref.get("key")
        if not key or not key_pattern.match(key):
            failures.append(f"ExternalSecret {name}: remoteRef.key {key!r} is not <env>/{service_account}/<name>")
if found == 0:
    failures.append("no ExternalSecret rendered")
for failure in failures:
    print(f"[externalsecret] {failure}", file=sys.stderr)
sys.exit(1 if failures else 0)
