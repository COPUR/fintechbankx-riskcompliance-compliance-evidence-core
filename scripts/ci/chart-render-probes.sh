#!/usr/bin/env bash
# Chart lint, render and refuse probes for deploy/helm/compliance-evidence-service, run by the deploy/helm job
# in .github/workflows/deployability.yml. It lives here, not inline, because GitHub
# turns a run: block that contains an expression into one expression and refuses
# the whole workflow above 21,000 characters (zero-job startup failure). The image
# tag comes from GITHUB_SHA, which the runner sets to the same value as github.sha.
set -e
: "${GITHUB_SHA:?GITHUB_SHA must be set (the runner sets it)}"
chart=deploy/helm/compliance-evidence-service
args="--set image.repository=ci/compliance-evidence-service --set image.tag=${GITHUB_SHA} --set externalSecret.remoteSecretName=ci/compliance-evidence-service/db-app"
# Schema owner credential, used only by the Flyway migration Job.
args="$args --set externalSecret.migrationSecretName=ci/compliance-evidence-service/db-migration"
# Aurora TLS (cicd-templates 4f0f266): the Terraform jdbc_url shape, verify-full against the mounted RDS CA bundle.
args="$args --set config.DB_URL=jdbc:postgresql://ci:5432/db?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem"
args="$args --set config.KAFKA_BOOTSTRAP_SERVERS=ci:9098"
args="$args --set config.OIDC_ISSUER_URI=https://ci/realms/fintechbankx --set config.OIDC_JWK_SET_URI=https://ci/certs"
# Computed screening facts (decision 0003): the customer context in the mesh and the realm token endpoint.
args="$args --set config.CUSTOMER_SERVICE_URL=http://customer-profile-kyc.customer.svc.cluster.local:8080"
args="$args --set config.OIDC_TOKEN_URI=https://ci/realms/fintechbankx/protocol/openid-connect/token"
# Empty required settings must fail the render, not produce pods that cannot start.
if helm template ci "$chart" --set image.repository=ci/x --set image.tag=x --set externalSecret.remoteSecretName=ci/db-app > /dev/null 2>&1; then
  echo "chart rendered without required runtime config"; exit 1
fi
helm lint "$chart" $args
helm template ci "$chart" $args > /dev/null
helm template ci "$chart" -f "$chart/values-dev.yaml" $args > /dev/null
rendered="$(helm template ci "$chart" $args)"
# Platform contract: mesh and network policy belong to the service-mesh repo (ingress from payments:
# mesh #11, 128e19d), secrets to the one ClusterSecretStore.
if grep -qE "kind: (NetworkPolicy|PeerAuthentication|AuthorizationPolicy|SecretStore)$" <<<"$rendered"; then
  echo "chart must not ship a NetworkPolicy, mesh policies or its own SecretStore" >&2
  exit 1
fi
grep -q 'name: "aws-secrets-manager"' <<<"$rendered"
grep -q 'app: "compliance-evidence-service"' <<<"$rendered"
# Platform outbox alerts key on service_id, scraped from the pod label; a wrong render must fail.
python3 scripts/ci/check-service-id-label.py svc-cmp-evidence <<<"$rendered"
# Platform component label (cicd-templates 335a345) on the pods and in every selector.
python3 scripts/ci/check-component-label.py <<<"$rendered"
# Aurora TLS (cicd-templates 4f0f266): ConfigMap rds-ca-bundle (global-bundle.pem) mounted read-only at
# /etc/fintechbankx/rds-ca, not optional, DB_SSL_ROOT_CERT exported, DB_URL with sslmode=verify-full.
python3 scripts/ci/check-database-ca.py <<<"$rendered"
helm template ci "$chart" -f "$chart/values-dev.yaml" $args | python3 scripts/ci/check-database-ca.py
# ...and the check catches a writable mount or an optional volume.
if sed '/readOnly: true/d' <<<"$rendered" | python3 scripts/ci/check-database-ca.py 2>/dev/null; then
  echo "database CA check accepted a mount without readOnly" >&2; exit 1
fi
if sed 's/^\( *\)name: "rds-ca-bundle"$/\1name: rds-ca-bundle\n\1optional: true/' <<<"$rendered" \
    | python3 scripts/ci/check-database-ca.py 2>/dev/null; then
  echo "database CA check accepted an optional rds-ca-bundle volume" >&2; exit 1
fi
# Rendering must fail for a PostgreSQL DB_URL without sslmode=verify-full.
for bad in "jdbc:postgresql://ci:5432/db?sslmode=require" "jdbc:postgresql://ci:5432/db"; do
  if out="$(helm template ci "$chart" $args --set "config.DB_URL=$bad" 2>&1)"; then
    echo "chart rendered with DB_URL $bad" >&2; exit 1
  fi
  grep -q "config.DB_URL must use sslmode=verify-full" <<<"$out"
done
# Ways around a substring check on DB_URL must fail too, each with its own message:
# a repeated sslmode (PgJDBC keeps the last), a decoy sslrootcert, a non-validating
# sslfactory, a second URL in another config key, and sslmode=verify-full hidden in a value.
bundle=/etc/fintechbankx/rds-ca/global-bundle.pem
base=jdbc:postgresql://ci:5432/db
reject_render() {
  local want="$1"; shift
  local out
  if out="$(helm template ci "$chart" $args "$@" 2>&1)"; then
    echo "chart rendered with $*" >&2; exit 1
  fi
  if ! grep -qF -- "$want" <<<"$out"; then
    echo "render with $* failed without \"$want\": $out" >&2; exit 1
  fi
}
reject_render "config.DB_URL must set sslmode exactly once (found 2)" --set "config.DB_URL=$base?sslmode=verify-full&sslrootcert=$bundle&sslmode=disable"
reject_render "config.DB_URL must set sslrootcert=$bundle exactly once" --set "config.DB_URL=$base?sslmode=verify-full&sslrootcert=/tmp/decoy.pem"
reject_render "config.DB_URL must not set sslfactory" --set "config.DB_URL=$base?sslmode=verify-full&sslrootcert=$bundle&sslfactory=org.postgresql.ssl.NonValidatingFactory"
# sslfactoryarg feeds a custom factory; service= loads host, port and TLS settings from a pg_service.conf
# entry the chart cannot see.
reject_render "config.DB_URL must not set sslfactoryarg" --set "config.DB_URL=$base?sslmode=verify-full&sslrootcert=$bundle&sslfactoryarg=/tmp/decoy.pem"
reject_render "config.DB_URL must not set service" --set "config.DB_URL=$base?sslmode=verify-full&sslrootcert=$bundle&service=pgsvc"
# PgJDBC reads parameter names case-sensitively (lower case): SSLMODE=verify-full is ignored by the driver, so
# a differently-cased TLS parameter is refused instead of being counted as the verified one or let through.
reject_render "config.DB_URL must spell SSLMODE in lower case" --set "config.DB_URL=$base?SSLMODE=verify-full&sslrootcert=$bundle"
reject_render "config.DB_URL must spell SSLROOTCERT in lower case" --set "config.DB_URL=$base?sslmode=verify-full&SSLROOTCERT=$bundle"
reject_render "config.DB_URL must not set sslfactory (it can bypass" --set "config.DB_URL=$base?sslmode=verify-full&sslrootcert=$bundle&SslFactory=org.postgresql.ssl.NonValidatingFactory"
reject_render "config.SPRING_DATASOURCE_URL is not allowed: it can redirect or override the datasource" --set "config.SPRING_DATASOURCE_URL=$base?sslmode=disable"
reject_render "config.SPRING_FLYWAY_URL is not allowed: it can redirect or override the datasource" --set "config.SPRING_FLYWAY_URL=$base?sslmode=disable"
# Every Spring datasource, Flyway, Liquibase and R2DBC key is refused by prefix, not only the URL ones.
for key in SPRING_LIQUIBASE_URL SPRING_R2DBC_URL SPRING_DATASOURCE_DRIVER_CLASS_NAME SPRING_FLYWAY_USER 'spring\.r2dbc\.url'; do
  reject_render "config.${key//\\/} is not allowed: it can redirect or override the datasource" \
    --set "config.$key=$base?sslmode=disable"
done
# ...and every spring.config.* key, including the file name (SPRING_CONFIG_NAME picks another application-*.yml).
reject_render "config.SPRING_CONFIG_NAME is not allowed: a spring.config.* property" \
  --set config.SPRING_CONFIG_NAME=override
reject_render "config.spring.config.name is not allowed: a spring.config.* property" \
  --set 'config.spring\.config\.name=override'
# Spring config locations and imports load property sources that can override DB_URL
# (spring.datasource.url from a file or configtree), in every relaxed spelling.
for key in SPRING_CONFIG_IMPORT 'spring\.config\.import' SPRING_CONFIG_IMPORT_0 \
    SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_CONFIG_ADDITIONALLOCATION 'spring\.config\.additional-location' \
    SPRING_CONFIG_LOCATION 'spring\.config\.location' 'spring_config_location'; do
  reject_render "config.${key//\\/} is not allowed: a spring.config.* property" \
    --set "config.$key=optional:file:/tmp/override.yml"
done
# Keys are matched after normalisation (upper case, every non-alphanumeric character dropped), so a
# dash inside an element, a dot or an index cannot spell a refused key past the check.
reject_render "config.SPRING_DATA-SOURCE_URL is not allowed: it can redirect or override the datasource" \
  --set "config.SPRING_DATA-SOURCE_URL=$base?sslmode=disable"
reject_render "config.SPRING_FLY-WAY_URL is not allowed: it can redirect or override the datasource" \
  --set "config.SPRING_FLY-WAY_URL=$base?sslmode=disable"
reject_render "config.SPRING_APPLICATION-JSON is not allowed: it can redirect or override the datasource" \
  --set 'config.SPRING_APPLICATION-JSON=spring.datasource.url=jdbc:postgresql://x/db'
reject_render "config.SPRING_CONFIG_IM-PORT is not allowed: a spring.config.* property" \
  --set "config.SPRING_CONFIG_IM-PORT=optional:file:/tmp/override.yml"
reject_render "config.spring.config.import[1] is not allowed: a spring.config.* property" \
  --set 'config.spring\.config\.import\[1\]=optional:configtree:/tmp/override/'
# JVM option variables add system properties (-Dspring.datasource.url=..., -Dspring.config.import=...)
# that outrank every environment variable, so none may come from install-time values. The guard checks the
# value of the three the JVM reads; the chart refuses those and the other spellings whatever the value.
for key in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
  reject_render "config.$key must not mention datasource" --set "config.$key=-Dspring.datasource.url=$base?sslmode=disable"
done
for key in JAVA_OPTS JAVA_TOOL-OPTIONS 'java\.tool\.options'; do
  reject_render "config.${key//\\/} must not be set: JVM options can override config.DB_URL and the TLS settings" \
    --set "config.$key=-Dspring.datasource.url=$base?sslmode=disable"
done
# Logging levels are fixed in application.yml: an install-time level could turn on wire logging
# (org.postgresql, org.apache.kafka), which prints credentials and personal data.
for key in LOGGING_LEVEL_ORG_POSTGRESQL LOGGING_LEVEL_ROOT 'logging\.level\.org\.apache\.kafka' LOGGING-LEVEL-ORG-POSTGRESQL; do
  reject_render "config.${key//\\/} must not be set: logging levels are fixed in application.yml" \
    --set "config.$key=TRACE"
done
# spring.main.* changes how the context starts: lazy initialization skips any startup assertion that is an
# ordinary singleton nothing injects, bean definition overriding or extra sources could replace one. The
# guards are hooks lazy initialization cannot skip, and the chart refuses the whole family whatever the value.
for key in SPRING_MAIN_LAZY_INITIALIZATION 'spring\.main\.lazy-initialization' \
    'spring\.main\.allow-bean-definition-overriding' SPRING_MAIN_SOURCES; do
  reject_render "config.${key//\\/} is not allowed: a spring.main.* property" \
    --set "config.$key=true"
done
# The service's own Flyway mode (compliance.database.flyway) is validate in every pod; only the migration Job,
# through its db-migrate profile, migrates. No install-time value may change it.
for key in COMPLIANCE_DATABASE_FLYWAY 'compliance\.database\.flyway' compliance-database-flyway; do
  reject_render "config.${key//\\/} must not be set: the schema belongs to the migration Job" --set-string "config.$key=migrate"
done
# Hibernate's schema management and Spring's SQL init change the schema at startup, before the runtime-role
# check runs; the schema belongs to the migration Job alone. The vendored platform guard (cicd-templates 73e1881)
# refuses both families on every route before the chart's own rule, so its message is the one expected.
for key in SPRING_JPA_HIBERNATE_DDL_AUTO 'spring\.jpa\.hibernate\.ddl-auto' SPRING_JPA_GENERATE_DDL \
    'spring\.jpa\.properties\.hibernate\.hbm2ddl\.auto' SPRING_SQL_INIT_MODE 'spring\.sql\.init\.schema-locations' \
    'hibernate\.hbm2ddl\.auto' 'jakarta\.persistence\.schema-generation\.database\.action'; do
  reject_render "config.${key//\\/} is not allowed: a schema-management setting" \
    --set-string "config.$key=update"
done
# DB_SSL_ROOT_CERT is the bundle the TLS startup assertions (DatabaseTlsGuard, KafkaTlsGuard; on by default)
# verify against; the chart sets it from the mounted bundle on every container, so a values key, empty or
# set, is refused.
for key in DB_SSL_ROOT_CERT 'db\.ssl\.root\.cert' db-ssl-root-cert DBSSLROOTCERT; do
  reject_render "config.${key//\\/} is not allowed: a TLS parameter name can replace the mounted CA" --set "config.$key="
  reject_render "config.${key//\\/} is not allowed: a TLS parameter name can replace the mounted CA" --set "config.$key=/tmp/decoy.pem"
done
# ...and the chart's normalised rule, in a spelling the guard's canonical form keeps apart.
reject_render "config.DB_SS.L_ROOT_CERT must not be set: the chart sets DB_SSL_ROOT_CERT from the mounted RDS CA bundle" \
  --set 'config.DB_SS\.L_ROOT_CERT=/tmp/decoy.pem'
# fintechbankx.tls.enforce (FINTECHBANKX_TLS_ENFORCE) is the test switch of the TLS startup assertions, which are
# on by default (only the local profile and tests turn them off): refused by the guard in every spelling it
# canonicalises, and by the chart's normalised rule in the one it keeps apart.
for key in FINTECHBANKX_TLS_ENFORCE 'fintechbankx\.tls\.enforce' FINTECHBANKX-TLS-ENFORCE 'fintechbankx\.tls\.enforce\[0\]'; do
  reject_render "config.${key//\\/} is not allowed: it can switch off the service's startup TLS assertion" \
    --set "config.$key=false"
done
reject_render "config.FINTECHBANKXTLSENFORCE must not be set: fintechbankx.tls.enforce is the test switch of the TLS startup assertions" \
  --set config.FINTECHBANKXTLSENFORCE=false
# No profile comes from config (the chart renders the only one, from kafka.runtime), so the local profile
# (a developer machine) cannot be activated, included, defaulted or grouped in, in any case, spelling or index.
local_profile="is not allowed: a profile can activate an application-<profile> config in the image (e.g. local)"
reject_render "config.SPRING_PROFILES_ACTIVE $local_profile" --set config.SPRING_PROFILES_ACTIVE=local
reject_render "config.SPRING_PROFILES_ACTIVE $local_profile" --set 'config.SPRING_PROFILES_ACTIVE=Kafka-Strimzi\,LOCAL'
reject_render "config.SPRING_PROFILES_ACTIVE $local_profile" --set 'config.SPRING_PROFILES_ACTIVE=x \, local'
reject_render "config.SPRING_PROFILES_INCLUDE $local_profile" --set 'config.SPRING_PROFILES_INCLUDE=x\, local'
reject_render "config.SPRING_PROFILES_ACTIVE_0 $local_profile" --set config.SPRING_PROFILES_ACTIVE_0=Local
reject_render "config.spring.profiles.active[1] $local_profile" --set 'config.spring\.profiles\.active\[1\]=local'
reject_render "config.spring.profiles.active $local_profile" --set 'config.spring\.profiles\.active=kafka-msk\,local'
reject_render "config.spring.profiles.include $local_profile" --set 'config.spring\.profiles\.include=LOCAL'
# The default profile and profile groups are other ways to the same profile.
reject_render "config.SPRING_PROFILES_DEFAULT $local_profile" --set config.SPRING_PROFILES_DEFAULT=local
reject_render "config.SPRING_PROFILES_DEFAULT_0 $local_profile" --set config.SPRING_PROFILES_DEFAULT_0=local
reject_render "config.SPRING_PROFILES_GROUP_KAFKA-MSK $local_profile" --set config.SPRING_PROFILES_GROUP_KAFKA-MSK=local
reject_render "config.spring.profiles.group.kafka-msk[0] $local_profile" --set 'config.spring\.profiles\.group\.kafka-msk\[0\]=local'
reject_render "config.SPRING_PROFILES_GROUP_KAFKA_MSK $local_profile" --set 'config.SPRING_PROFILES_GROUP_KAFKA_MSK=kafka-msk\, Local'
# A config value is one string: a list or map would render as its Go form ("[local]", "map[...]") and
# carry a refused name past the value checks.
reject_render "config.SPRING_PROFILES_ACTIVE $local_profile" --set 'config.SPRING_PROFILES_ACTIVE={local}'
reject_render "config.SERVICE_CALLERS must be a single string" --set 'config.SERVICE_CALLERS={svc-a,svc-b}'
reject_render "config.OIDC_AUDIENCE must be a single string" --set 'config.OIDC_AUDIENCE={svc-cmp-evidence,other}'
# The chart has no extraEnv / env list: config.* is the only values route into the pods' environment, so
# the guard's adapter (compliance.guard) passes none. Adding such a list must map it in the adapter.
if grep -qE '^(extraEnv|extraEnvFrom|env|envFrom|javaToolOptions):' "$chart/values.yaml"; then
  echo "values.yaml gained an env list; map it in the fbx.guard adapter (compliance.guard) first" >&2; exit 1
fi
reject_render "config.DB_URL must use sslmode=verify-full (with sslrootcert=$bundle)" --set "config.DB_URL=$base?sslmode=require&applicationName=sslmode=verify-full"
# Vendored guard (cicd-templates 6b6c317; fbx.guard through compliance.guard). The chart renders the only
# Spring profile from kafka.runtime (msk -> kafka-msk, strimzi -> kafka-strimzi); no config key sets one.
helm template ci "$chart" $args --set kafka.runtime=strimzi --set kafkaClientTls.enabled=true \
  --set config.KAFKA_SECURITY_PROTOCOL=SSL | grep -q 'SPRING_PROFILES_ACTIVE: "kafka-strimzi"'
profile_key="is not allowed: a profile can activate an application-<profile> config in the image"
reject_render "config.SPRING_PROFILES_ACTIVE $profile_key" --set config.SPRING_PROFILES_ACTIVE=kafka-msk
reject_render "config.SPRING_PROFILES_ACTIVE $profile_key" --set 'config.SPRING_PROFILES_ACTIVE=kafka-msk\,localstack'
reject_render 'kafka.runtime must be "", msk or strimzi (got "bogus")' --set kafka.runtime=bogus
# Strimzi mutual TLS needs the client certificate Secret (kafkaClientTls).
reject_render "kafka.runtime strimzi needs kafkaClientTls.enabled" \
  --set kafka.runtime=strimzi --set config.KAFKA_SECURITY_PROTOCOL=SSL
reject_render "kafka.runtime strimzi needs kafkaClientTls.enabled" \
  --set kafka.runtime=strimzi --set config.KAFKA_SECURITY_PROTOCOL=SSL --set kafkaClientTls.enabled=true --set kafkaClientTls.secretName=
# Kafka TLS on the chart's routes: SASL_SSL with msk, SSL with strimzi, never PLAINTEXT; broker host names verified.
reject_render "config.KAFKA_SECURITY_PROTOCOL must be SASL_SSL or SSL" --set config.KAFKA_SECURITY_PROTOCOL=PLAINTEXT
reject_render "config.KAFKA_SECURITY_PROTOCOL must be SASL_SSL with kafka.runtime msk" --set config.KAFKA_SECURITY_PROTOCOL=SSL
reject_render "config.KAFKA_SECURITY_PROTOCOL must be SSL with kafka.runtime strimzi" \
  --set kafka.runtime=strimzi --set kafkaClientTls.enabled=true
reject_render "config.SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM must be https" \
  --set config.SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM=
# A Spring placeholder or Kubernetes variable in DB_URL is resolved after the check and could add sslmode=disable.
reject_render "config.DB_URL must not contain '\${' or '\$('" \
  --set "config.DB_URL=$base?sslmode=verify-full&sslrootcert=$bundle&options=\${DB_OPTIONS}"
# The chart's own rules stay where the guard is narrower: JVM option keys are refused whatever their value,
# a wrapper driver is not a jdbc:postgresql URL, and keys are matched upper-cased with every non-alphanumeric
# character dropped (the guard's canonical form keeps a dot inside a word).
reject_render "config.JAVA_TOOL_OPTIONS must not be set: JVM options" --set config.JAVA_TOOL_OPTIONS=-Xmx512m
reject_render "config.DB_URL must be a jdbc:postgresql URL" \
  --set "config.DB_URL=jdbc:p6spy:postgresql://ci:5432/db?sslmode=verify-full&sslrootcert=$bundle"
reject_render "config.SPRING.PRO.FILES.ACTIVE must not be set: the chart renders the only profile" \
  --set 'config.SPRING\.PRO\.FILES\.ACTIVE=kafka-msk'
reject_render "config.SPRING_DATA.SOURCE_URL must not be set: config.DB_URL is the only database URL" \
  --set "config.SPRING_DATA\.SOURCE_URL=jdbc:postgresql://other:5432/db?sslmode=verify-full&sslrootcert=$bundle"
# Kubernetes expands $(VAR) in a container env value from earlier entries and the envFrom Secret: the
# migration Job's DB_USERNAME=$(DB_MIGRATION_USERNAME) would make the runtime role the schema owner.
reject_render "config.DB_USERNAME must not contain '\$('" --set 'config.DB_USERNAME=$(DB_MIGRATION_USERNAME)'
reject_render "config.OIDC_AUDIENCE must not contain '\$('" --set 'config.OIDC_AUDIENCE=$(HOSTNAME)'
# Template interpolation: a newline in a key or a secret name the templates write must not add keys.
injection="$(mktemp)"
printf 'config:\n  "OIDC_AUDIENCE\\nSPRING_CONFIG_IMPORT": optional:file:/tmp/override.yml\n' > "$injection"
reject_render "is not a ConfigMap key ([-._a-zA-Z0-9]+)" -f "$injection"
reject_render "must not contain a control character" \
  --set "externalSecret.remoteSecretName=ci/compliance-evidence-service/db-app"$'\n'"        property: username"
reject_render "must not contain a control character" \
  --set "externalSecret.migrationSecretName=ci/compliance-evidence-service/db-migration"$'\n'"        version: x"
# Every other value a template interpolates is quoted (strings) or rendered with int (numbers), so a newline
# cannot close its line: each value below renders, and no rendered document carries the key "injected".
no_injection() {
  python3 -c '
import sys, yaml
def walk(node):
    if isinstance(node, dict):
        if "injected" in node:
            sys.exit("a newline in " + sys.argv[1] + " added the key injected")
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for value in node:
            walk(value)
for doc in yaml.safe_load_all(sys.stdin):
    walk(doc)
' "$1"
}
for spec in image.pullPolicy:10 image.tag:4 serviceAccount.name:6 serviceAccount.roleArn:4 \
    externalSecret.secretStoreRef.name:4 externalSecret.secretStoreRef.kind:4 externalSecret.refreshInterval:2 \
    kafkaClientTls.secretName:18 config.OIDC_AUDIENCE:2 podLabels.team:8 \
    replicaCount:2 service.port:14 service.managementPort:14 migrationJob.backoffLimit:2 \
    migrationJob.activeDeadlineSeconds:2 topologySpread.maxSkew:10 podDisruptionBudget.minAvailable:2 \
    autoscaling.minReplicas:2 autoscaling.maxReplicas:2 \
    autoscaling.targetCPUUtilizationPercentage:10; do
  path="${spec%%:*}"; indent="$(printf '%*s' "${spec##*:}" '')"
  extra=""; [ "$path" = replicaCount ] && extra="--set autoscaling.enabled=false"
  out="$(helm template ci "$chart" $args $extra --set kafkaClientTls.enabled=true \
    --set-string "$path=x"$'\n'"${indent}injected: true" 2>&1)" || { echo "render with a newline in $path failed: $out" >&2; exit 1; }
  no_injection "$path" <<<"$out"
done
# cicd-templates 6b6c317, README "Vendoring the guard" step 4: the must_fail cases of
# scripts/ci/validate-chart.sh ("DB_URL materialised from the ExternalSecret" to "databaseCa.configMapName
# null") and the cases of the helm-unittest suites database_ca_test, kafka_client_tls_names_test,
# documentdb_names_test and property_reading_names_test that this chart has a route for: config.*,
# kafka.runtime and databaseCa.*. The chart has no extraEnv, extraEnvFrom, javaToolOptions or
# values-driven ExternalSecret key (the check above fails if values.yaml gains one), so the cases on
# those routes do not apply, nor do the databaseCa.enabled=false ones (the adapter always passes
# enabled: true). Each case keeps the platform's --expect pattern (an ERE), so it cannot pass because
# the render failed for another reason. These show that every values route reaches fbx.guard.
reject_render_re() {
  local want="$1"; shift
  local out
  if out="$(helm template ci "$chart" $args "$@" 2>&1)"; then
    echo "chart rendered with $*" >&2; exit 1
  fi
  if ! grep -qE -- "$want" <<<"$out"; then
    echo "render with $* failed without /$want/: $out" >&2; exit 1
  fi
}
render_ok() {
  local out
  out="$(helm template ci "$chart" $args "$@" 2>&1)" || { echo "render with $* failed: $out" >&2; exit 1; }
}
strimzi="--set-string kafka.runtime=strimzi --set-string config.KAFKA_SECURITY_PROTOCOL=SSL --set kafkaClientTls.enabled=true"
db=jdbc:postgresql://db.example.internal:5432/db_cmp_evidence_dev
# Database CA mount, pinned while databaseCa.enabled (fbx.validateDatabaseCa): /app is the image WORKDIR
# and Spring Boot reads optional:file:./config/, and another ConfigMap or key replaces the trust anchor.
reject_render_re 'databaseCa.mountPath must be /etc/fintechbankx/rds-ca \(got "/app/config"\)' \
  --set-string databaseCa.mountPath=/app/config --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=/app/config/global-bundle.pem"
reject_render_re 'databaseCa.mountPath must be /etc/fintechbankx/rds-ca' --set-string databaseCa.mountPath=/etc/fintechbankx/rds-ca/
reject_render_re 'databaseCa.key must be global-bundle.pem \(got "other-bundle.pem"\)' \
  --set-string databaseCa.key=other-bundle.pem --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/other-bundle.pem"
reject_render_re 'databaseCa.configMapName must be rds-ca-bundle \(got "any-config"\)' --set-string databaseCa.configMapName=any-config
reject_render_re 'databaseCa.configMapName is required while databaseCa.enabled' --set databaseCa.configMapName=null
# ...including a newline that would add a key under the volume (the pin refuses every other value).
reject_render_re 'databaseCa.configMapName must be rds-ca-bundle' \
  --set-string "databaseCa.configMapName=rds-ca-bundle"$'\n'"            optional: true"
# DB_URL (fbx.validateJdbcUrl).
reject_render_re 'config.DB_URL must use sslmode=verify-full' --set-string "config.DB_URL=$db?sslmode=require"
reject_render_re 'config.DB_URL must set sslmode exactly once' \
  --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=$bundle&sslmode=require"
reject_render_re 'config.DB_URL must use sslmode=verify-full' \
  --set-string "config.DB_URL=$db?xsslmode=verify-full&sslmode=disable&sslrootcert=$bundle"
reject_render_re "config.DB_URL must set sslrootcert=$bundle exactly once" --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=/tmp/attacker.pem"
reject_render_re "config.DB_URL must set sslrootcert=$bundle exactly once" --set-string "config.DB_URL=$db?sslmode=verify-full"
reject_render_re 'config.DB_URL must not set sslfactory' \
  --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=$bundle&sslfactory=org.postgresql.ssl.NonValidatingFactory"
reject_render_re 'config.DB_URL must not set sslhostnameverifier' \
  --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=$bundle&sslhostnameverifier=com.example.AllowAll"
reject_render_re 'config.DB_URL must not set sslpasswordcallback' \
  --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=$bundle&sslpasswordcallback=com.example.Callback"
reject_render_re 'config.DB_URL must not set service' --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=$bundle&service=cmp"
reject_render_re 'config.DB_URL must not percent-encode' --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=$bundle&sslmode%3Ddisable"
reject_render_re 'config.DB_URL has a query parameter name that is not plain' \
  --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=$bundle&ssl%6Dode=disable"
reject_render_re 'config.DB_URL must spell SSLMODE in lower case' --set-string "config.DB_URL=$db?SSLMODE=verify-full&sslrootcert=$bundle"
reject_render_re 'config.DB_URL must carry TLS parameters only in the query string' \
  --set-string "config.DB_URL=jdbc:postgresql://db.example.internal:5432/db;sslmode=disable?sslmode=verify-full&sslrootcert=$bundle"
reject_render_re 'config.DB_URL must use sslmode=verify-full' --set-string 'config.DB_URL=jdbc:aws-wrapper:postgresql://db.example.internal:5432/db?sslmode=require'
reject_render_re 'config.DB_URL must be a jdbc:' --set-string 'config.DB_URL=postgresql://db.example.internal:5432/db?sslmode=disable'
reject_render_re 'config.DB_URL must not contain' --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=$bundle&x=\${FBX_TAIL}"
render_ok --set-string "config.DB_URL=$db?sslmode=verify-full&sslrootcert=$bundle&ApplicationName=cmp&connectTimeout=10"
# Datasource, config, profile, SSL bundle and switch names.
reject_render_re 'config.db.url is DB_URL in another spelling' --set-string "config.db\.url=$db?sslmode=verify-full&sslrootcert=$bundle"
reject_render_re 'config.SPRING_DATASOURCE_URL is not allowed' --set-string "config.SPRING_DATASOURCE_URL=$db?sslmode=require"
reject_render_re 'config.SPRING_FLYWAY_URL is not allowed' --set-string "config.SPRING_FLYWAY_URL=$db?sslmode=verify-full&sslrootcert=$bundle"
reject_render_re 'config.SPRING_APPLICATION_JSON is not allowed' --set-json 'config.SPRING_APPLICATION_JSON="{\"spring.datasource.url\":\"x\"}"'
reject_render_re 'config.SPRING_DATASOURCE_HIKARI_JDBC_URL is not allowed' --set-string config.SPRING_DATASOURCE_HIKARI_JDBC_URL=x
reject_render_re 'config.SPRING_CONFIG_IMPORT is not allowed' --set-string config.SPRING_CONFIG_IMPORT=optional:file:/tmp/override.yml
reject_render_re 'config.spring.config.activate.on-profile is not allowed' --set-string 'config.spring\.config\.activate\.on-profile=local'
reject_render_re 'config.SPRING_SSL_BUNDLE_PEM_DOCUMENTDB_TRUSTSTORE_CERTIFICATE is not allowed' \
  --set-string config.SPRING_SSL_BUNDLE_PEM_DOCUMENTDB_TRUSTSTORE_CERTIFICATE=file:/tmp/any-ca.pem
reject_render_re 'config.PGJDBC_SSL_FACTORY is not allowed' --set-string config.PGJDBC_SSL_FACTORY=org.postgresql.ssl.NonValidatingFactory
reject_render_re 'config.FINTECHBANKX_TLS_ENFORCE is not allowed' --set-string config.FINTECHBANKX_TLS_ENFORCE=false
reject_render_re 'config.SPRING_PROFILES_DEFAULT is not allowed' --set-string config.SPRING_PROFILES_DEFAULT=local
reject_render_re 'config.JDK_JAVA_OPTIONS must not mention .*kafka' --set-string 'config.JDK_JAVA_OPTIONS=-Dspring.Kaf_ka.producer.acks=0'
# Kafka protocol and profile.
reject_render_re 'kafka.runtime' --set-string kafka.runtime=local
reject_render_re 'config.KAFKA_SECURITY_PROTOCOL must be SASL_SSL or SSL' --set-string kafka.runtime=msk --set-string config.KAFKA_SECURITY_PROTOCOL=PLAINTEXT
reject_render_re 'must be SSL with kafka.runtime strimzi' --set-string kafka.runtime=strimzi --set-string config.KAFKA_SECURITY_PROTOCOL=SASL_SSL
reject_render_re 'is set but kafka.runtime is empty' --set-string kafka.runtime= --set-string config.KAFKA_SECURITY_PROTOCOL=SSL
reject_render_re 'config.spring.kafka.security.pro_tocol must be SASL_SSL or SSL' --set-string 'config.spring\.kafka\.security\.pro_tocol=PLAINTEXT'
# Kafka client TLS names: trust and key stores, TLS versions, per-client and properties spellings.
kafka_tls='is not allowed: a Kafka client TLS setting'
reject_render_re "config.SPRING_KAFKA_SSL_TRUSTSTORE_LOCATION $kafka_tls" --set-string config.SPRING_KAFKA_SSL_TRUSTSTORE_LOCATION=file:/etc/other/any.jks
reject_render_re "config.SPRING_KAFKA_SSL_TRUST_STORE_LOCATION $kafka_tls" --set-string config.SPRING_KAFKA_SSL_TRUST_STORE_LOCATION=file:/etc/other/any.jks
reject_render_re "config.spring.kafka.ssl.trust-store-certificates $kafka_tls" --set-string 'config.spring\.kafka\.ssl\.trust-store-certificates=-----BEGIN CERTIFICATE-----'
reject_render_re "config.spring.kafka.s-sl.trust-store-location $kafka_tls" --set-string 'config.spring\.kafka\.s-sl\.trust-store-location=file:/etc/other/any.jks'
reject_render_re "config.SPRING_KAFKA_SSL_PROTOCOL $kafka_tls" --set-string config.SPRING_KAFKA_SSL_PROTOCOL=TLSv1
reject_render_re "config.SPRING_KAFKA_ADMIN_SSL_TRUST_STORE_CERTIFICATES $kafka_tls" \
  --set-string 'config.SPRING_KAFKA_ADMIN_SSL_TRUST_STORE_CERTIFICATES=-----BEGIN CERTIFICATE-----'
reject_render_re "config.spring.kafka.streams.ssl.key-password $kafka_tls" --set-string 'config.spring\.kafka\.streams\.ssl\.key-password=x'
reject_render_re "config.spring.kafka.properties.ssl.truststore.location $kafka_tls" \
  --set-string 'config.spring\.kafka\.properties\.ssl\.truststore\.location=/etc/other/any.jks'
reject_render_re "config.SPRING_KAFKA_PROPERTIES_SSL_TRUSTSTORE_LOCATION $kafka_tls" \
  --set-string config.SPRING_KAFKA_PROPERTIES_SSL_TRUSTSTORE_LOCATION=/etc/other/any.jks
reject_render_re "config.spring.kafka.properties\[ssl.truststore.type\] $kafka_tls" \
  --set-string 'config.spring\.kafka\.properties\[ssl\.truststore\.type\]=PEM'
reject_render_re "config.spring.kafka.pro-perties.ssl.truststore.location $kafka_tls" \
  --set-string 'config.spring\.kafka\.pro-perties\.ssl\.truststore\.location=/etc/other/any.jks'
reject_render_re "config.spring.kafka.streams.properties.ssl.keystore.location $kafka_tls" \
  --set-string 'config.spring\.kafka\.streams\.properties\.ssl\.keystore\.location=/etc/other/any.p12'
reject_render_re "config.SPRING_KAFKA_SSL_TRUST_STORE_LOCATION $kafka_tls" $strimzi \
  --set-string config.SPRING_KAFKA_SSL_TRUST_STORE_LOCATION=file:/etc/other/any.jks
reject_render_re "config.SPRING_KAFKA_SSL_TRUST_STORE_CERTIFICATES $kafka_tls" $strimzi \
  --set-string 'config.SPRING_KAFKA_SSL_TRUST_STORE_CERTIFICATES=-----BEGIN CERTIFICATE-----'
reject_render_re 'config.SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM must be https' \
  --set-string config.SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM=
render_ok --set-string config.SPRING_KAFKA_PROPERTIES_SSL_ENDPOINT_IDENTIFICATION_ALGORITHM=https
# The MSK IAM sasl.* settings come from the image's kafka-msk profile; the vendored platform guard
# (cicd-templates 73e1881) refuses every Kafka SASL or JAAS name. Other Kafka names stay allowed.
reject_render "config.SPRING_KAFKA_PROPERTIES_SASL_MECHANISM is not allowed: a Kafka SASL or JAAS setting" --set-string config.SPRING_KAFKA_PROPERTIES_SASL_MECHANISM=AWS_MSK_IAM
reject_render "config.spring.kafka.properties.sasl.client.callback.handler.class is not allowed: a Kafka SASL or JAAS setting" \
  --set-string 'config.spring\.kafka\.properties\.sasl\.client\.callback\.handler\.class=software.amazon.msk.auth.iam.IAMClientCallbackHandler'
render_ok --set-string config.KAFKA_BOOTSTRAP_SERVERS=b-1.msk.example.internal:9098 \
  --set-string config.SPRING_KAFKA_CONSUMER_GROUP_ID=compliance-evidence --set-string config.KAFKA_LISTENER_CONCURRENCY=2
# KAFKA_TLS_CERT/KEY/CA only from valueFrom.secretKeyRef: the chart renders them from kafkaClientTls.secretName
# (deployment.yaml); a config key, in any spelling, is refused.
reject_render_re 'config.KAFKA_TLS_CERT is not allowed' $strimzi --set-string 'config.KAFKA_TLS_CERT=-----BEGIN CERTIFICATE-----'
reject_render_re 'config.KAFKA_TLS_CA is not allowed: .*Secret' $strimzi --set-string 'config.KAFKA_TLS_CA=-----BEGIN CERTIFICATE-----'
reject_render_re 'config.KAFKA_TLS_KEY is not allowed: .*Secret' --set-string config.KAFKA_TLS_KEY=x
reject_render_re 'config.Kafka_Tls_Cert is not allowed' --set-string config.Kafka_Tls_Cert=x
python3 -c '
import sys, yaml
docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
app = [d for d in docs if d["kind"] == "Deployment"][0]["spec"]["template"]["spec"]["containers"][0]
env = {e["name"]: e for e in app["env"] if e["name"].startswith("KAFKA_TLS_")}
if sorted(env) != ["KAFKA_TLS_CA", "KAFKA_TLS_CERT", "KAFKA_TLS_KEY"]:
    sys.exit("strimzi render lacks KAFKA_TLS_CERT/KEY/CA: %s" % sorted(env))
for name, e in env.items():
    if set(e) != {"name", "valueFrom"} or set(e["valueFrom"]) != {"secretKeyRef"}:
        sys.exit(name + " must come only from valueFrom.secretKeyRef: %r" % e)
print("KAFKA_TLS_CERT/KEY/CA from valueFrom.secretKeyRef only")
' < <(helm template ci "$chart" $args $strimzi)
# Computed screening facts (decision 0003). The chart renders CUSTOMER_SERVICE_URL, OIDC_TOKEN_URI, SVC_CLIENT_ID,
# COMPLIANCE_V1_CALLER_FACTS_ENABLED and the two list ages from config.*, every one required
# (compliance.requiredConfig: a dropped key fails the render), and SVC_CLIENT_SECRET only from
# valueFrom.secretKeyRef on identity.clientSecret (an existing Secret, synced by platform; the chart ships no
# ExternalSecret or SecretStore for it). The values are checked: the flag is "true" or "false", the ages are
# ISO-8601 durations, the token endpoint is https (the credential travels in the request body), the customer URL
# is an absolute http(s) URL. A property spelling of these settings, or the credential, in config.* is refused.
for key in CUSTOMER_SERVICE_URL OIDC_TOKEN_URI SVC_CLIENT_ID COMPLIANCE_V1_CALLER_FACTS_ENABLED \
    COMPLIANCE_LIST_MAX_AGE_SANCTIONS COMPLIANCE_LIST_MAX_AGE_PEP; do
  reject_render "config.$key is required" --set "config.$key=null"
  reject_render "config.$key is required" --set-string "config.$key="
done
reject_render 'config.SVC_CLIENT_SECRET must not be set' --set-string config.SVC_CLIENT_SECRET=x
reject_render 'config.COMPLIANCE_IDENTITY_CLIENT_SECRET must not be set' --set-string config.COMPLIANCE_IDENTITY_CLIENT_SECRET=x
reject_render 'config.compliance.identity.client-secret must not be set' --set-string 'config.compliance\.identity\.client-secret=x'
reject_render 'config.COMPLIANCE_IDENTITY_TOKEN_URI must not be set' --set-string config.COMPLIANCE_IDENTITY_TOKEN_URI=https://ci/token
reject_render 'config.COMPLIANCE_CUSTOMER_BASE_URL must not be set' --set-string config.COMPLIANCE_CUSTOMER_BASE_URL=http://ci
reject_render 'config.COMPLIANCE_LISTS_SANCTIONS_MAX_AGE must not be set' --set-string config.COMPLIANCE_LISTS_SANCTIONS_MAX_AGE=P1D
reject_render 'config.compliance.lists.pep-max-age must not be set' --set-string 'config.compliance\.lists\.pep-max-age=P35D'
for bad in yes 1 True FALSE "" ; do
  [ -n "$bad" ] || continue
  reject_render 'config.COMPLIANCE_V1_CALLER_FACTS_ENABLED must be "true" or "false"' --set-string "config.COMPLIANCE_V1_CALLER_FACTS_ENABLED=$bad"
done
render_ok --set-string config.COMPLIANCE_V1_CALLER_FACTS_ENABLED=true
for key in COMPLIANCE_LIST_MAX_AGE_SANCTIONS COMPLIANCE_LIST_MAX_AGE_PEP; do
  for bad in 1d 24h P PT P1Y P-1D 86400; do
    reject_render "config.$key must be an ISO-8601 duration" --set-string "config.$key=$bad"
  done
  render_ok --set-string "config.$key=PT12H"
  render_ok --set-string "config.$key=P35D"
done
reject_render 'config.OIDC_TOKEN_URI must be an https:// URL' --set-string config.OIDC_TOKEN_URI=http://ci/realms/fintechbankx/protocol/openid-connect/token
reject_render 'config.OIDC_TOKEN_URI must be an https:// URL' --set-string config.OIDC_TOKEN_URI=ci/token
reject_render 'config.CUSTOMER_SERVICE_URL must be an absolute http:// or https:// URL' --set-string config.CUSTOMER_SERVICE_URL=customer:8080
reject_render 'config.CUSTOMER_SERVICE_URL must be an absolute http:// or https:// URL' --set-string 'config.CUSTOMER_SERVICE_URL=http://customer:8080 x'
render_ok --set-string config.CUSTOMER_SERVICE_URL=https://customer.example.internal
reject_render 'identity.clientSecret.secretName is required' --set-string identity.clientSecret.secretName=
reject_render 'identity.clientSecret.key is required' --set-string identity.clientSecret.key=
reject_render 'identity.clientSecret.secretName must be a DNS-1123 name' --set-string 'identity.clientSecret.secretName=Svc Client'
reject_render 'identity.clientSecret.secretName must be a DNS-1123 name' --set-string identity.clientSecret.secretName=x$'\n'"                  injected: true"
reject_render 'identity.clientSecret.key must be a Secret key' --set-string 'identity.clientSecret.key=client secret'
python3 -c '
import sys, yaml
docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
app = [d for d in docs if d["kind"] == "Deployment"][0]["spec"]["template"]["spec"]["containers"][0]
env = {e["name"]: e for e in app["env"]}
secret = env.get("SVC_CLIENT_SECRET")
if secret is None:
    sys.exit("the app pods lack SVC_CLIENT_SECRET")
if set(secret) != {"name", "valueFrom"} or set(secret["valueFrom"]) != {"secretKeyRef"}:
    sys.exit("SVC_CLIENT_SECRET must come only from valueFrom.secretKeyRef: %r" % secret)
ref = secret["valueFrom"]["secretKeyRef"]
if ref != {"name": "svc-client-ci", "key": "credential-ci"}:
    sys.exit("SVC_CLIENT_SECRET must read identity.clientSecret.secretName/key: %r" % ref)
if ref.get("optional"):
    sys.exit("SVC_CLIENT_SECRET must not be optional: a pod without the credential must not start")
configmaps = [d for d in docs if d["kind"] == "ConfigMap"]
for cm in configmaps:
    for key in cm.get("data") or {}:
        if "SECRET" in key.upper() and key != "OIDC_JWK_SET_URI":
            sys.exit("ConfigMap %s carries a secret-looking key %s" % (cm["metadata"]["name"], key))
config = [d for d in docs if d["kind"] == "ConfigMap" and d["metadata"]["name"] == "compliance-evidence-service"][0]["data"]
for key, want in {"SVC_CLIENT_ID": "svc-cmp-evidence", "COMPLIANCE_V1_CALLER_FACTS_ENABLED": "false",
                  "COMPLIANCE_LIST_MAX_AGE_SANCTIONS": "P1D", "COMPLIANCE_LIST_MAX_AGE_PEP": "P35D"}.items():
    if config.get(key) != want:
        sys.exit("ConfigMap %s is %r, expected %r by default" % (key, config.get(key), want))
for key in ("CUSTOMER_SERVICE_URL", "OIDC_TOKEN_URI"):
    if not config.get(key):
        sys.exit("ConfigMap lacks " + key)
job = [d for d in docs if d["kind"] == "Job"][0]["spec"]["template"]["spec"]["containers"][0]
if any(e["name"] == "SVC_CLIENT_SECRET" for e in job.get("env") or []):
    sys.exit("the migration Job must not mount SVC_CLIENT_SECRET: it only migrates")
print("SVC_CLIENT_SECRET from valueFrom.secretKeyRef only (identity.clientSecret), screening config rendered")
' < <(helm template ci "$chart" $args --set-string identity.clientSecret.secretName=svc-client-ci --set-string identity.clientSecret.key=credential-ci)
# DocumentDB names (the service has none; the guard keeps them out of the environment).
mongodb='is not allowed: a spring.data.mongodb.\* property'
reject_render_re "config.SPRING_DATA_MONGODB_URI $mongodb" --set-string 'config.SPRING_DATA_MONGODB_URI=mongodb://docdb.example.internal:27017/db?tls=false'
reject_render_re "config.spring.data.mongodb.uri $mongodb" --set-string 'config.spring\.data\.mongodb\.uri=mongodb://docdb.example.internal:27017/db?tls=false'
reject_render_re "config.spring.data.mongo-db.uri $mongodb" --set-string 'config.spring\.data\.mongo-db\.uri=mongodb://docdb.example.internal:27017/db?tls=false'
reject_render_re "config.spring.data\[mongodb\].uri $mongodb" --set-string 'config.spring\.data\[mongodb\]\.uri=mongodb://docdb.example.internal:27017/db?tls=false'
reject_render_re "config.SPRING_DATA_MONGODB_SSL_ENABLED $mongodb" --set-string config.SPRING_DATA_MONGODB_SSL_ENABLED=false
reject_render_re "config.spring.data.mongodb.password $mongodb" --set-string 'config.spring\.data\.mongodb\.password=x'
reject_render_re 'config.MONGODB_URI is not allowed: MONGODB_URI is the DocumentDB connection string' \
  --set-string 'config.MONGODB_URI=mongodb://docdb.example.internal:27017/db?tls=false'
# Property reading: Spring Boot splits an env name on '.' only and drops '_' inside an element.
reject_render_re "config.spring.kafka.s_sl.trust-store-type $kafka_tls" $strimzi --set-string 'config.spring\.kafka\.s_sl\.trust-store-type=PKCS12'
reject_render_re "config.spring.kafka.con_sumer.ssl.trust-store-type $kafka_tls" --set-string 'config.spring\.kafka\.con_sumer\.ssl\.trust-store-type=PKCS12'
reject_render_re "config.spring.kafka.streams.pro_perties.ssl.truststore.location $kafka_tls" \
  --set-string 'config.spring\.kafka\.streams\.pro_perties\.ssl\.truststore\.location=/etc/other/any.jks'
render_ok --set-string 'config.spring\.kafka\.properties\.ssl\.endpoint_identification\.algorithm=https'
reject_render_re "config.spring.data.mongo_db.host $mongodb" --set-string 'config.spring\.data\.mongo_db\.host=placeholder-host'
reject_render_re "config.spring.da_ta.mongodb.port $mongodb" --set-string 'config.spring\.da_ta\.mongodb\.port=27018'
reject_render_re 'config.spring.pro_files.active is not allowed' --set-string 'config.spring\.pro_files\.active=local'
reject_render_re 'config.spring.con_fig.import is not allowed: a spring.config.\* property' --set-string 'config.spring\.con_fig\.import=optional:file:/etc/other/override.yml'
reject_render_re 'config.spring.s_sl.bundle.pem.documentdb.truststore.certificate is not allowed: an SSL bundle property' \
  --set-string 'config.spring\.s_sl\.bundle\.pem\.documentdb\.truststore\.certificate=file:/etc/other/any-ca.pem'
reject_render_re "config.fintech_bankx.tls.enforce is not allowed: it can switch off the service's startup TLS assertion" \
  --set-string 'config.fintech_bankx\.tls\.enforce=false'
reject_render_re 'config.spring.data_source.url is not allowed: it can redirect or override the datasource' --set-string 'config.spring\.data_source\.url=jdbc:h2:mem:x'
reject_render_re 'config.spring.data_source.driver-class-name is not allowed: it can redirect or override the datasource' \
  --set-string 'config.spring\.data_source\.driver-class-name=org.h2.Driver'
reject_render_re 'config.spring.fly_way.url is not allowed: it can redirect or override the datasource' --set-string 'config.spring\.fly_way\.url=jdbc:h2:mem:x'
# Platform scrapes the management port through these pod annotations; the outbox alerts depend on them.
python3 scripts/ci/check-scrape-annotations.py <<<"$rendered"
if helm template ci "$chart" $args | sed '/prometheus.io\/path:/d' \
    | python3 scripts/ci/check-scrape-annotations.py 2>/dev/null; then
  echo "scrape annotation check accepted a render without prometheus.io/path" >&2; exit 1
fi
if helm template ci "$chart" $args --set 'podLabels.fintechbankx\.io/service-id=svc-other' \
    | python3 scripts/ci/check-service-id-label.py svc-cmp-evidence 2>/dev/null; then
  echo "service-id label check accepted a wrong label" >&2; exit 1
fi
# The relay stays off until the mesh contract gives this namespace MSK egress (runbook step 1).
grep -q '"OUTBOX_RELAY_ENABLED": "false"' <<<"$rendered"
helm template ci "$chart" $args --set-string config.OUTBOX_RELAY_ENABLED=true | grep -q '"OUTBOX_RELAY_ENABLED": "true"'
# KafkaTlsGuard is on by default, relay on or off, so the default env must already give the producer a
# TLS protocol or no default-cluster pod would start.
grep -q '"KAFKA_SECURITY_PROTOCOL": "SASL_SSL"' <<<"$rendered"
grep -q 'SPRING_PROFILES_ACTIVE: "kafka-msk"' <<<"$rendered"
# Migration owner credential: its own ExternalSecret, and the Job cannot run without it.
grep -q 'secretKey: "DB_MIGRATION_USERNAME"' <<<"$rendered"
grep -q "name: compliance-evidence-service-db-migration" <<<"$rendered"
reject_render "externalSecret.migrationSecretName is required" --set externalSecret.migrationSecretName=
# ...and it must be another secret than the runtime one: a single credential for both would run Flyway
# and the service as the same role, and the insert-only guarantee (decision 0002) would not hold.
single_user="externalSecret.migrationSecretName must not be the runtime secret (externalSecret.remoteSecretName)"
reject_render "$single_user" --set externalSecret.migrationSecretName=ci/compliance-evidence-service/db-app
reject_render "$single_user" --set externalSecret.remoteSecretName=ci/compliance-evidence-service/db-migration
reject_render "$single_user" --set externalSecret.migrationSecretName=ci/compliance-evidence-service/db-app/
reject_render "$single_user" --set 'externalSecret.migrationSecretName= ci/compliance-evidence-service/db-app '
# Secrets contract: each ExternalSecret is labelled with the service account and reads only <env>/<sa>/... keys.
sa="$(python3 -c 'import sys, yaml; print(yaml.safe_load(open(sys.argv[1]))["serviceAccount"]["name"])' "$chart/values.yaml")"
python3 scripts/ci/check-externalsecrets.py "$sa" <<<"$rendered"
# ...and the check catches a wrong render.
if helm template ci "$chart" $args --set externalSecret.remoteSecretName=ci/other-service/db-app \
    | python3 scripts/ci/check-externalsecrets.py "$sa" 2>/dev/null; then
  echo "ExternalSecret check accepted a key outside <env>/$sa/" >&2; exit 1
fi
# Flyway runs only in the pre-install/pre-upgrade migration Job (cicd-templates 335a345 labels): the app
# pods never reference the db-migration secret, the Job runs the app image with args [migrate], and no
# Service, PDB, NetworkPolicy or Deployment selector matches its component=db-migration pods.
python3 scripts/ci/check-migration-job.py "$sa" <<<"$rendered"
helm template ci "$chart" -f "$chart/values-dev.yaml" $args | python3 scripts/ci/check-migration-job.py "$sa"
# ...and the check catches each way back to the old shape.
# Platform guard cases of cicd-templates 73e1881 (README "Vendoring the guard", step 4), schema_management_names_test,
# unmatched_bracket_names_test and the validate-chart.sh must_fail cases added since 6b6c317, on the routes this
# chart has: config (extraEnv, javaToolOptions and values-driven ExternalSecret keys are not values of this chart). Each failing case keeps the platform's
# pattern (an ERE), so it cannot pass because the render failed for another reason. A control must not be refused
# by fbx.guard; the chart's own stricter rules (a SPRINGJPA* or HIBERNATE* prefix, no extraEnv) may still refuse it.
gvec="$(mktemp -d)"
n_g=0
chart_own_refusal='must not be set: the schema belongs to the migration Job|is not supported by this chart'
guard_render_re() {
  local want="$1"; shift
  local out
  if out="$(helm template ci "$chart" $args "$@" 2>&1)"; then
    echo "chart rendered with $*" >&2; exit 1
  fi
  if ! grep -qE -- "$want" <<<"$out"; then
    echo "render with $* failed without /$want/: $out" >&2; exit 1
  fi
}
guard_case_re() {
  n_g=$((n_g + 1))
  printf '%s\n' "$2" > "$gvec/g$n_g.yaml"
  guard_render_re "$1" -f "$gvec/g$n_g.yaml"
}
guard_ok() {
  local out
  n_g=$((n_g + 1))
  printf '%s\n' "$2" > "$gvec/g$n_g.yaml"
  out="$(helm template ci "$chart" $args -f "$gvec/g$n_g.yaml" 2>&1)" && return 0
  if ! grep -qE -- "$chart_own_refusal" <<<"$out"; then
    echo "fbx.guard refused the control $1: $out" >&2; exit 1
  fi
}
# schema_management_names_test.yaml
guard_case_re 'config.SPRING_JPA_HIBERNATE_DDL_AUTO is not allowed: a schema-management setting' \
  '{"config": {"SPRING_JPA_HIBERNATE_DDL_AUTO": "create-drop"}}'
guard_case_re 'config.spring.jpa.generate-ddl is not allowed: a schema-management setting' \
  '{"config": {"spring.jpa.generate-ddl": "true"}}'
guard_case_re 'config.SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO is not allowed: a schema-management setting' \
  '{"config": {"SPRING_JPA_PROPERTIES_HIBERNATE_HBM2DDL_AUTO": "update"}}'
guard_case_re 'config.SPRING_SQLINIT_MODE is not allowed: a schema-management setting' \
  '{"config": {"SPRING_SQLINIT_MODE": "always"}}'
guard_case_re 'config.JAVAX_PERSISTENCE_SCHEMA_GENERATION_DATABASE_ACTION is not allowed: a schema-management setting' \
  '{"config": {"JAVAX_PERSISTENCE_SCHEMA_GENERATION_DATABASE_ACTION": "drop-and-create"}}'
guard_case_re 'config.spring.j_pa.hibernate.ddl-auto is not allowed: a schema-management setting' \
  '{"config": {"spring.j_pa.hibernate.ddl-auto": "create"}}'
guard_ok 'accepts names that only start like the schema-management families (control)' \
  '{"config": {"SPRING_DATA_JPA_REPOSITORIES_BOOTSTRAP_MODE": "deferred", "SPRING_JPAX_NOTE": "x", "APP_HIBERNATE_STATS": "off", "HIBERNATES_NOTE": "x", "JAKARTA_MAIL_HOST": "smtp.example.test"}}'
guard_case_re 'config.JDK_JAVA_OPTIONS must not mention .*hibernate' \
  '{"config": {"JDK_JAVA_OPTIONS": "-Dhibernate.hbm2ddl.auto=update"}}'
# unmatched_bracket_names_test.yaml: not applicable, only extraEnv can carry such a name and this chart has no extraEnv route
# validate-chart.sh must_fail and vendored_must_fail cases added since 6b6c317 (env -> config,
# additionalEnv -> extraEnv, jvmOptions -> javaToolOptions)
guard_render_re 'config.SPRING_MAIN_LAZY_INITIALIZATION is not allowed: a spring.main.\* property' --set-string config.SPRING_MAIN_LAZY_INITIALIZATION=true
guard_render_re 'config.SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG is not allowed: a Kafka SASL or JAAS setting' --set-string 'config.SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG=x.LoginModule required;'
guard_render_re 'config.SPRING_JPA_HIBERNATE_DDL_AUTO is not allowed: a schema-management setting' --set-string config.SPRING_JPA_HIBERNATE_DDL_AUTO=create-drop
guard_render_re 'config.SPRING_CONFIG_IMPORT_0_ is not allowed' --set-string config.SPRING_CONFIG_IMPORT_0_=optional:file:./x.yml
guard_render_re 'config.SPRING_MAIN_LAZYINITIALIZATION is not allowed: a spring.main.\* property' --set-string config.SPRING_MAIN_LAZYINITIALIZATION=true
guard_render_re 'config.spring.main.sources is not allowed: a spring.main.\* property' --set-string 'config.spring\.main\.sources=file:./beans.xml'
guard_render_re 'config.SPRING_JPA_GENERATE_DDL is not allowed: a schema-management setting' --set-string config.SPRING_JPA_GENERATE_DDL=true
guard_render_re 'config.SPRING_KAFKA_PROPERTIES_SASL_MECHANISM is not allowed: a Kafka SASL or JAAS setting' --set-string config.SPRING_KAFKA_PROPERTIES_SASL_MECHANISM=PLAIN
guard_render_re 'config.SPRING_KAFKA_PROPERTIES_SASLJAASCONFIG is not allowed: a Kafka SASL or JAAS setting' --set-string config.SPRING_KAFKA_PROPERTIES_SASLJAASCONFIG=x
mutated() {
  python3 -c '
import sys, yaml
docs = [d for d in yaml.safe_load_all(sys.stdin) if d]
one = lambda kind: [d for d in docs if d["kind"] == kind][0]
app = one("Deployment")["spec"]["template"]
job = one("Job")
exec(sys.argv[1])
yaml.safe_dump_all(docs, sys.stdout)
' "$1" <<<"$rendered"
}
reject_mutation() {
  if mutated "$2" | python3 scripts/ci/check-migration-job.py "$sa" 2>/dev/null; then
    echo "migration Job check accepted $1" >&2; exit 1
  fi
}
reject_mutation "the migration secret in the app pods (envFrom)" \
  'app["spec"]["containers"][0]["envFrom"].append({"secretRef": {"name": "compliance-evidence-service-db-migration"}})'
reject_mutation "the migration secret in the app pods (secretKeyRef)" \
  'app["spec"]["containers"][0]["env"].append({"name": "DB_MIGRATION_PASSWORD", "valueFrom": {"secretKeyRef": {"name": "compliance-evidence-service-db-migration", "key": "DB_MIGRATION_PASSWORD"}}})'
reject_mutation "Job pods labelled component=service" \
  'job["spec"]["template"]["metadata"]["labels"]["app.kubernetes.io/component"] = "service"'
reject_mutation "Job pods without the service account name label" \
  'job["spec"]["template"]["metadata"]["labels"]["app.kubernetes.io/name"] = "flyway"'
# The Job's pods run without the Istio sidecar: Aurora egress is a NetworkPolicy on the name label, and
# without native sidecars an injected proxy keeps the Job from completing.
reject_mutation "Job pods with the sidecar injected (podLabels value)" \
  'job["spec"]["template"]["metadata"]["labels"]["sidecar.istio.io/inject"] = "true"'
reject_mutation "Job pods without the sidecar.istio.io/inject label" \
  'del job["spec"]["template"]["metadata"]["labels"]["sidecar.istio.io/inject"]'
reject_mutation "Job pods whose annotation turns injection back on" \
  'job["spec"]["template"]["metadata"].setdefault("annotations", {})["sidecar.istio.io/inject"] = "true"'
reject_mutation "a Job that is not a pre-upgrade hook" \
  'job["metadata"]["annotations"]["helm.sh/hook"] = "pre-install"'
reject_mutation "a Job without ttlSecondsAfterFinished" 'del job["spec"]["ttlSecondsAfterFinished"]'
reject_mutation "a Job without activeDeadlineSeconds" 'del job["spec"]["activeDeadlineSeconds"]'
reject_mutation "a Job without backoffLimit" 'del job["spec"]["backoffLimit"]'
reject_mutation "a Job with another DB_URL" \
  '[e.update(value="jdbc:postgresql://other:5432/db") for e in job["spec"]["template"]["spec"]["containers"][0]["env"] if e["name"] == "DB_URL"]'
reject_mutation "a Job with a writable root filesystem" \
  'job["spec"]["template"]["spec"]["containers"][0]["securityContext"]["readOnlyRootFilesystem"] = False'
reject_mutation "a Job that does not run the migrate mode" \
  'job["spec"]["template"]["spec"]["containers"][0]["args"] = []'
reject_mutation "a Service selecting by name only" \
  'del one("Service")["spec"]["selector"]["app.kubernetes.io/component"]'
reject_mutation "a NetworkPolicy selecting the Job pods" \
  'docs.append({"kind": "NetworkPolicy", "metadata": {"name": "np"}, "spec": {"podSelector": {"matchExpressions": [{"key": "app.kubernetes.io/component", "operator": "In", "values": ["db-migration"]}]}}})'
reject_mutation "a PDB selecting by name only" \
  'del one("PodDisruptionBudget")["spec"]["selector"]["matchLabels"]["app.kubernetes.io/component"]'
# Platform metric tags: app = the service account, squad = compliance (application.yml reads both).
grep -qx "  METRICS_APP: \"$sa\"" <<<"$rendered"
grep -qx '  METRICS_SQUAD: "compliance"' <<<"$rendered"
