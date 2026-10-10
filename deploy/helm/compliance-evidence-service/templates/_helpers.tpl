{{- define "compliance.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "compliance.selectorLabels" -}}
app.kubernetes.io/name: {{ include "compliance.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: service
{{- end -}}

{{- define "compliance.labels" -}}
{{ include "compliance.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "compliance.secretName" -}}
{{ include "compliance.name" . }}-db
{{- end -}}

{{- define "compliance.migrationSecretName" -}}
{{ include "compliance.name" . }}-db-migration
{{- end -}}

{{/*
Flyway migration Job (templates/migration-job.yaml). Its pods carry
app.kubernetes.io/name=<service account>, on which the mesh grants Aurora
egress, and app.kubernetes.io/component=db-migration (cicd-templates 335a345);
every selector of the app pods includes component=service, so none selects
them. The Job's ServiceAccount, Job and the db-migration ExternalSecret share
the name <service account>-db-migration.
*/}}
{{- define "compliance.migrationName" -}}
{{ .Values.serviceAccount.name }}-db-migration
{{- end -}}

{{- define "compliance.migrationSelectorLabels" -}}
app.kubernetes.io/name: {{ .Values.serviceAccount.name | quote }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: db-migration
{{- end -}}

{{- define "compliance.migrationLabels" -}}
{{ include "compliance.migrationSelectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/*
Hook resources the Job needs on a first install, when no regular resource of
the release exists yet: created before the Job (lower weight) and deleted once
every hook has succeeded, so the schema owner's credential exists in the
namespace only while a migration runs. A failed run leaves them for
inspection; the next install or upgrade replaces them.
*/}}
{{- define "compliance.migrationPrerequisiteHook" -}}
helm.sh/hook: pre-install,pre-upgrade
helm.sh/hook-weight: "-10"
helm.sh/hook-delete-policy: before-hook-creation,hook-succeeded
{{- end -}}

{{/* The migration Job reads the schema owner's credential; refuse a render without it. */}}
{{- define "compliance.requireMigrationSecret" -}}
{{- if not .Values.externalSecret.enabled -}}
{{- fail "externalSecret.enabled must be true: the migration Job reads the schema owner's credential (externalSecret.migrationSecretName)" -}}
{{- end -}}
{{- $_ := required "externalSecret.migrationSecretName is required: the migration Job runs Flyway as the schema owner (Secrets Manager <env>/<service account>/db-migration)" .Values.externalSecret.migrationSecretName -}}
{{- /* The single-user fallback: one credential for the Job and the service would run Flyway and the
       service as the same role (decision 0002). Names are compared trimmed and without a trailing "/". */ -}}
{{- $migration := trimSuffix "/" (trim (toString .Values.externalSecret.migrationSecretName)) -}}
{{- $runtime := trimSuffix "/" (trim (toString (default "" .Values.externalSecret.remoteSecretName))) -}}
{{- if eq $migration $runtime -}}
{{- fail "externalSecret.migrationSecretName must not be the runtime secret (externalSecret.remoteSecretName): the migration Job runs Flyway as the schema owner and the service as the runtime role, never one credential for both" -}}
{{- end -}}
{{- end -}}

{{/* Shared by the app pods and the migration Job pods. */}}
{{- define "compliance.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
fsGroup: 10001
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "compliance.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
{{- end -}}

{{- define "compliance.image" -}}
{{ required "image.repository is required" .Values.image.repository }}:{{ required "image.tag is required" .Values.image.tag }}
{{- end -}}

{{/* RDS CA bundle volume. Not optional: without the bundle the pod must not start. */}}
{{- define "compliance.databaseCaVolume" -}}
- name: database-ca
  configMap:
    name: {{ .Values.databaseCa.configMapName | quote }}
    items:
      - key: {{ .Values.databaseCa.key | quote }}
        path: {{ .Values.databaseCa.key | quote }}
{{- end -}}

{{- define "compliance.databaseCaMount" -}}
- name: database-ca
  mountPath: {{ .Values.databaseCa.mountPath | quote }}
  readOnly: true
{{- end -}}

{{/*
The two ExternalSecrets' entries (externalsecret.yaml renders them from here),
as JSON so that the guard's adapter and the template read the same list:
data is the runtime credential, extraData the schema owner's (db-migration).
*/}}
{{- define "compliance.externalSecretData" -}}
{{- $es := .Values.externalSecret -}}
{{- $runtime := toString (default "" $es.remoteSecretName) -}}
{{- $migration := toString (default "" $es.migrationSecretName) -}}
{{- toJson (dict
      "data" (list (dict "secretKey" "SPRING_DATASOURCE_PASSWORD" "property" "password" "remoteSecretName" $runtime))
      "extraData" (list
        (dict "secretKey" "DB_MIGRATION_USERNAME" "property" "username" "remoteSecretName" $migration)
        (dict "secretKey" "DB_MIGRATION_PASSWORD" "property" "password" "remoteSecretName" $migration))) -}}
{{- end -}}

{{/*
The datasource / TLS guard, called first by deployment.yaml and
migration-job.yaml (a failure stops the whole render). fbx.guard is the
platform guard, vendored unchanged in _fbx_helpers.tpl (cicd-templates a4f0072;
its README, "Vendoring the guard"), fed through this adapter. Every route of
this chart into a container's environment is mapped:
  - config: config.* (the ConfigMap the app pods load with envFrom; the
    migration Job renders config.DB_URL and config.DB_USERNAME into its env);
  - databaseCa: always enabled (the bundle volume is not optional), so
    config.DB_URL must carry sslrootcert=<mountPath>/<key> exactly once;
  - kafka.runtime: the only Spring profile (fbx.kafkaProfile, configmap.yaml);
  - externalSecret.data / extraData: the runtime and db-migration secret keys
    and remote names (compliance.externalSecretData).
The chart has no extraEnv, envFrom, extraEnvFrom, javaToolOptions or
ExternalSecret dataFrom value, so the adapter passes none (CI fails if
values.yaml gains an env list). The chart's own rules follow the guard:
compliance.validateDatabaseTls (normalised config keys, list values, a
jdbc:postgresql DB_URL), compliance.validateEnvValues ('$(') and
compliance.validateKafkaRuntime (strimzi needs kafkaClientTls).
*/}}
{{- define "compliance.guard" -}}
{{- $secrets := include "compliance.externalSecretData" . | fromJson -}}
{{- include "fbx.guard" (dict "Values" (dict
      "config" .Values.config
      "databaseCa" (dict "enabled" true "mountPath" .Values.databaseCa.mountPath "key" .Values.databaseCa.key)
      "kafka" (dict "runtime" (.Values.kafka | default dict).runtime)
      "externalSecret" (dict "enabled" .Values.externalSecret.enabled
        "data" $secrets.data "extraData" $secrets.extraData))) -}}
{{- include "compliance.validateDatabaseTls" . -}}
{{- include "compliance.validateEnvValues" . -}}
{{- include "compliance.validateKafkaRuntime" . -}}
{{- end -}}

{{/*
Aurora TLS (cicd-templates 4f0f266): the database connection must verify the
server certificate and host name against the mounted RDS CA bundle. fbx.guard
parses config.DB_URL the way PgJDBC does (exactly one sslmode=verify-full and
one sslrootcert=<bundle>, no sslfactory, sslfactoryarg, sslhostnameverifier,
sslpasswordcallback or service, lower-case TLS names, no '${' or '$(') and
refuses datasource, Flyway, Liquibase, R2DBC, spring.config.*,
spring.profiles.*, SSL bundle, fintechbankx.tls.* and TLS parameter names in
their relaxed-binding spellings. This chart keeps the rules where the guard is
narrower.

Each key is normalised before it is matched (upper case, then every character
that is not a letter or digit dropped), so a dash, dot, underscore or index
anywhere in the key spells the same name: spring.config.import[0],
SPRING_CONFIG_IM-PORT and SPRINGCONFIGIMPORT0 are all SPRINGCONFIGIMPORT0, and
SPRING_DATA.SOURCE_URL is SPRINGDATASOURCEURL (the guard's canonical form keeps
that dot). Refused: SPRING_DATASOURCE_*, SPRING_FLYWAY_*, SPRING_LIQUIBASE_*,
SPRING_R2DBC_* and SPRING_APPLICATION_JSON by prefix (the user name too; the
chart sets every database setting); every SPRING_CONFIG_* key (the only
configtree this service may read is one the chart itself renders on the fixed
mount optional:configtree:/etc/fintechbankx/config/, none today); every
SPRING_PROFILES_* key (the chart renders the only profile, from kafka.runtime);
the JVM option variables JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS and
JAVA_OPTS whatever their value (a system property outranks every environment
variable; the guard only checks the value of the first three); LOGGING_LEVEL_*
(an install-time level could turn on wire logging, which prints credentials
and personal data); and DB_SSL_ROOT_CERT, empty or set (the switch of the TLS
startup assertions DatabaseTlsGuard and KafkaTlsGuard; the chart sets it from
the mounted bundle on every container). Every config value must be one string:
a list or map would render as its Go form. config.DB_URL must start with
jdbc:postgresql: exactly (the guard also lets a wrapper driver through). The
application's DatabaseTlsGuard repeats the URL checks at startup, in the
migration Job too.

Takes a dict: key (the config key) and value (its value).
*/}}
{{- define "compliance.refusedConfigKey" -}}
{{- $key := toString .key -}}
{{- $name := regexReplaceAll "[^A-Z0-9]" (upper $key) "" -}}
{{- if or (kindIs "slice" .value) (kindIs "map" .value) -}}
{{- printf "config.%s must be a single string: a list or map would render as its Go form and bypass the value checks" $key -}}
{{- else if regexMatch "^SPRING(DATASOURCE|FLYWAY|LIQUIBASE|R2DBC|APPLICATIONJSON)" $name -}}
{{- printf "config.%s must not be set: config.DB_URL is the only database URL (sslmode=verify-full) and the chart sets every other database setting" $key -}}
{{- else if regexMatch "^SPRINGCONFIG" $name -}}
{{- printf "config.%s must not be set: it loads configuration that can override config.DB_URL" $key -}}
{{- else if regexMatch "^SPRINGPROFILES" $name -}}
{{- printf "config.%s must not be set: the chart renders the only profile (SPRING_PROFILES_ACTIVE) from kafka.runtime" $key -}}
{{- else if regexMatch "^(JAVATOOLOPTIONS|JDKJAVAOPTIONS|JAVAOPTIONS|JAVAOPTS)$" $name -}}
{{- printf "config.%s must not be set: JVM options can override config.DB_URL and the TLS settings" $key -}}
{{- else if regexMatch "^LOGGINGLEVEL" $name -}}
{{- printf "config.%s must not be set: logging levels are fixed in application.yml" $key -}}
{{- else if eq $name "DBSSLROOTCERT" -}}
{{- printf "config.%s must not be set: the chart sets DB_SSL_ROOT_CERT from the mounted RDS CA bundle, and it switches on the TLS startup assertions (DatabaseTlsGuard, KafkaTlsGuard)" $key -}}
{{- end -}}
{{- end -}}

{{- define "compliance.validateDatabaseTls" -}}
{{- range $key, $value := .Values.config -}}
{{- with include "compliance.refusedConfigKey" (dict "key" $key "value" $value) -}}
{{- fail . -}}
{{- end -}}
{{- end -}}
{{- $url := toString (default "" (index .Values.config "DB_URL")) -}}
{{- if and $url (not (hasPrefix "jdbc:postgresql:" $url)) -}}
{{- fail (printf "config.DB_URL must be a jdbc:postgresql URL with sslmode=verify-full (with sslrootcert=%s)" (include "compliance.databaseCaFile" .)) -}}
{{- end -}}
{{- end -}}

{{/*
Kubernetes expands $(VAR) in a container's env[].value from the container's
earlier env entries and its envFrom sources after the chart has checked the
text. The migration Job renders config.DB_URL and config.DB_USERNAME into env
values next to the db-migration Secret (envFrom), so DB_USERNAME
$(DB_MIGRATION_USERNAME) would name the schema owner as the runtime role.
Every config value is refused with '$(' (the app pods load config through
envFrom, where nothing is expanded, but the same values feed the Job), and so
is a databaseCa path (DB_SSL_ROOT_CERT on every container).
*/}}
{{- define "compliance.validateEnvValues" -}}
{{- range $key, $value := .Values.config -}}
{{- if contains "$(" (toString $value) -}}
{{- fail (printf "config.%s must not contain '$(': the migration Job renders config values (DB_URL, DB_USERNAME) into container env values, where Kubernetes expands $(VAR) from the db-migration Secret after this check" $key) -}}
{{- end -}}
{{- end -}}
{{- if contains "$(" (include "compliance.databaseCaFile" .) -}}
{{- fail "databaseCa.mountPath and databaseCa.key must not contain '$(': the chart renders them into DB_SSL_ROOT_CERT, where Kubernetes would expand $(VAR)" -}}
{{- end -}}
{{- end -}}

{{/*
kafka.runtime selects the only Spring profile (fbx.kafkaProfile: msk ->
kafka-msk, strimzi -> kafka-strimzi; anything else fails). The kafka-strimzi
profile reads the client certificate, key and cluster CA from
KAFKA_TLS_CERT/KEY/CA, which the chart maps from kafkaClientTls.secretName
only with kafkaClientTls.enabled.
*/}}
{{- define "compliance.validateKafkaRuntime" -}}
{{- $profile := include "fbx.kafkaProfile" (dict "Values" (dict "kafka" (.Values.kafka | default dict))) -}}
{{- $tls := .Values.kafkaClientTls | default dict -}}
{{- if and (eq $profile "kafka-strimzi") (not (and $tls.enabled (trim (toString (default "" $tls.secretName))))) -}}
{{- fail "kafka.runtime strimzi needs kafkaClientTls.enabled with a kafkaClientTls.secretName: the kafka-strimzi profile reads the client certificate, key and cluster CA from that Secret (KAFKA_TLS_CERT, KAFKA_TLS_KEY, KAFKA_TLS_CA)" -}}
{{- end -}}
{{- end -}}

{{- define "compliance.databaseCaFile" -}}
{{- printf "%s/%s" (trimSuffix "/" .Values.databaseCa.mountPath) .Values.databaseCa.key -}}
{{- end -}}
