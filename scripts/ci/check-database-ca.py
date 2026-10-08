#!/usr/bin/env python3
"""Reads a rendered chart on stdin and checks the Aurora TLS contract
(cicd-templates 4f0f266): the Deployment mounts ConfigMap rds-ca-bundle
(key global-bundle.pem) read-only at /etc/fintechbankx/rds-ca, the volume is
not optional, the container exports DB_SSL_ROOT_CERT with the bundle path, and
a jdbc:postgresql DB_URL in the ConfigMap uses sslmode=verify-full with
sslrootcert pointing at the same file."""
import sys

import yaml

CONFIGMAP, KEY, MOUNT = "rds-ca-bundle", "global-bundle.pem", "/etc/fintechbankx/rds-ca"
BUNDLE = f"{MOUNT}/{KEY}"

docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
deployments = [d for d in docs if d["kind"] == "Deployment"]
configmaps = [d for d in docs if d["kind"] == "ConfigMap"]
problems = []

if len(deployments) != 1:
    problems.append(f"expected one Deployment, found {len(deployments)}")
else:
    pod = deployments[0]["spec"]["template"]["spec"]
    volumes = [v for v in pod.get("volumes", []) if (v.get("configMap") or {}).get("name") == CONFIGMAP]
    if len(volumes) != 1:
        problems.append(f"expected one volume from ConfigMap {CONFIGMAP}, found {len(volumes)}")
    else:
        volume = volumes[0]
        source = volume["configMap"]
        if source.get("optional"):
            problems.append(f"volume {volume['name']}: ConfigMap {CONFIGMAP} must not be optional")
        if source.get("items") != [{"key": KEY, "path": KEY}]:
            problems.append(f"volume {volume['name']}: items must be [{{key: {KEY}, path: {KEY}}}], got {source.get('items')!r}")
        for container in pod["containers"]:
            mounts = [m for m in container.get("volumeMounts", []) if m.get("name") == volume["name"]]
            if len(mounts) != 1 or mounts[0].get("mountPath") != MOUNT or mounts[0].get("readOnly") is not True:
                problems.append(f"container {container['name']}: needs {volume['name']} mounted readOnly at {MOUNT}, got {mounts!r}")
            env = {e["name"]: e.get("value") for e in container.get("env", [])}
            if env.get("DB_SSL_ROOT_CERT") != BUNDLE:
                problems.append(f"container {container['name']}: DB_SSL_ROOT_CERT must be {BUNDLE}, got {env.get('DB_SSL_ROOT_CERT')!r}")

urls = [(cm["metadata"]["name"], (cm.get("data") or {}).get("DB_URL", "")) for cm in configmaps]
for name, url in urls:
    if url.startswith("jdbc:postgresql:"):
        if "sslmode=verify-full" not in url:
            problems.append(f"ConfigMap {name}: DB_URL lacks sslmode=verify-full")
        if f"sslrootcert={BUNDLE}" not in url:
            problems.append(f"ConfigMap {name}: DB_URL lacks sslrootcert={BUNDLE}")
if not any(url for _, url in urls):
    problems.append("no ConfigMap with DB_URL rendered")

if problems:
    sys.exit("database CA check failed:\n  " + "\n  ".join(problems))
print(f"ConfigMap {CONFIGMAP} ({KEY}) mounted read-only at {MOUNT}, not optional; DB_SSL_ROOT_CERT={BUNDLE}; DB_URL sslmode=verify-full")
