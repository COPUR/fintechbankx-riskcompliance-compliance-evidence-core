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
app.kubernetes.io/name: {{ .Values.serviceAccount.name }}
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
    name: {{ .Values.databaseCa.configMapName }}
    items:
      - key: {{ .Values.databaseCa.key }}
        path: {{ .Values.databaseCa.key }}
{{- end -}}

{{- define "compliance.databaseCaMount" -}}
- name: database-ca
  mountPath: {{ .Values.databaseCa.mountPath }}
  readOnly: true
{{- end -}}

{{/*
Aurora TLS (cicd-templates 4f0f266): the database connection must verify the
server certificate and host name against the mounted RDS CA bundle;
sslmode=require encrypts but trusts any certificate.

The query after the first "?" is read the way PgJDBC reads it (split on "&",
name before the first "="). PgJDBC lets the last of repeated parameters win,
so sslmode and sslrootcert must each appear exactly once; a custom sslfactory
(and its sslfactoryarg), sslhostnameverifier or sslpasswordcallback would
replace the verification, and service= would load host, port and TLS settings
from a pg_service.conf entry the chart cannot see. PgJDBC reads parameter names
case-sensitively (SSLMODE is not sslmode and is ignored by the driver), so the
required sslmode and sslrootcert are matched exactly in lower case and a
differently-cased spelling of any of these names is refused by name rather than
ignored. Values are compared undecoded (PgJDBC decodes values, not names), so
an encoded value fails closed. The ConfigMap exports every config key, so any
SPRING_DATASOURCE_*, SPRING_FLYWAY_*, SPRING_LIQUIBASE_*, SPRING_R2DBC_* or
SPRING_APPLICATION_JSON key (a second URL, a driver class, driver properties)
would override DB_URL and is refused by prefix, and so is every SPRING_CONFIG_*
key (SPRING_CONFIG_IMPORT, SPRING_CONFIG_ADDITIONAL_LOCATION,
SPRING_CONFIG_LOCATION, SPRING_CONFIG_NAME, also indexed), which picks or loads
a file or configtree that can set the URL; the only configtree this service may
read is one the chart itself renders on the fixed mount
optional:configtree:/etc/fintechbankx/config/ (none today), never one named by a
values key. JVM option variables (JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS,
_JAVA_OPTIONS, JAVA_OPTS) are refused because a system property outranks every
environment variable, and LOGGING_LEVEL_* because an install-time level could
turn on wire logging (org.postgresql, org.apache.kafka), which prints
credentials and personal data. Each key is normalised before it is matched
(upper case, then every character that is not a letter or digit dropped), so a
dash, dot, underscore or index anywhere in the key spells the same name:
spring.config.import[0], SPRING_CONFIG_IM-PORT and SPRINGCONFIGIMPORT0 are all
SPRINGCONFIGIMPORT0. The app and the Flyway migration Job (schema owner) share
DB_URL. The application's DatabaseTlsGuard repeats the URL checks at startup,
in the Job too.

DB_SSL_ROOT_CERT is refused as a config key, empty or set, in any spelling
(DBSSLROOTCERT): it is the switch of the TLS startup assertions
(DatabaseTlsGuard, KafkaTlsGuard run whenever it is set), and the chart sets it
from the mounted bundle on every container (compliance.databaseCaFile). The
local profile (a developer machine) is refused in SPRING_PROFILES_ACTIVE,
SPRING_PROFILES_INCLUDE and SPRING_PROFILES_DEFAULT, also indexed
(spring.profiles.active[1], SPRING_PROFILES_ACTIVE_0), and in every profile
group (SPRING_PROFILES_GROUP_*, spring.profiles.group.<name>[0]): the value is
split on "," and each name trimmed and lower-cased, so it is caught in any case
and at any position of the list (Kafka-Strimzi,LOCAL; x , local). Only the
name local itself is refused. Every config value must be one string: a list or
map ({local}, [local]) would render as its Go form and carry a refused name
past the value check.

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
{{- else if regexMatch "^(JAVATOOLOPTIONS|JDKJAVAOPTIONS|JAVAOPTIONS|JAVAOPTS)$" $name -}}
{{- printf "config.%s must not be set: JVM options can override config.DB_URL and the TLS settings" $key -}}
{{- else if regexMatch "^LOGGINGLEVEL" $name -}}
{{- printf "config.%s must not be set: logging levels are fixed in application.yml" $key -}}
{{- else if eq $name "DBSSLROOTCERT" -}}
{{- printf "config.%s must not be set: the chart sets DB_SSL_ROOT_CERT from the mounted RDS CA bundle, and it switches on the TLS startup assertions (DatabaseTlsGuard, KafkaTlsGuard)" $key -}}
{{- else if or (regexMatch "^SPRINGPROFILES(ACTIVE|INCLUDE|DEFAULT)[0-9]*$" $name) (hasPrefix "SPRINGPROFILESGROUP" $name) -}}
{{- $local := false -}}
{{- range $profile := splitList "," (toString .value) -}}
{{- if eq (lower (trim $profile)) "local" -}}
{{- $local = true -}}
{{- end -}}
{{- end -}}
{{- if $local -}}
{{- printf "config.%s must not activate the local profile: it is for a developer machine, never for a cluster" $key -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "compliance.validateDatabaseTls" -}}
{{- $bundle := "/etc/fintechbankx/rds-ca/global-bundle.pem" -}}
{{- range $key, $value := .Values.config -}}
{{- with include "compliance.refusedConfigKey" (dict "key" $key "value" $value) -}}
{{- fail . -}}
{{- end -}}
{{- end -}}
{{- $url := toString (default "" (index .Values.config "DB_URL")) -}}
{{- if $url -}}
{{- if not (hasPrefix "jdbc:postgresql:" $url) -}}
{{- fail (printf "config.DB_URL must be a jdbc:postgresql URL with sslmode=verify-full (with sslrootcert=%s)" $bundle) -}}
{{- end -}}
{{- $query := "" -}}
{{- if contains "?" $url -}}
{{- $query = (splitn "?" 2 $url)._1 -}}
{{- end -}}
{{- $sslmode := list -}}
{{- $rootcert := list -}}
{{- $tlsParameters := list "sslmode" "sslrootcert" "sslfactory" "sslfactoryarg" "sslhostnameverifier" "sslpasswordcallback" "service" -}}
{{- range $param := splitList "&" $query -}}
{{- $kv := splitn "=" 2 $param -}}
{{- $k := $kv._0 -}}
{{- $v := toString (default "" $kv._1) -}}
{{- if and (ne $k (lower $k)) (has (lower $k) $tlsParameters) -}}
{{- fail (printf "config.DB_URL must not set %s: PgJDBC reads parameter names case-sensitively, so only the lower-case %s is the verified setting" $k (lower $k)) -}}
{{- else if eq $k "sslmode" -}}
{{- $sslmode = append $sslmode $v -}}
{{- else if eq $k "sslrootcert" -}}
{{- $rootcert = append $rootcert $v -}}
{{- else if has $k (list "sslfactory" "sslfactoryarg" "sslhostnameverifier" "sslpasswordcallback") -}}
{{- fail (printf "config.DB_URL must not set %s: it replaces certificate or host name verification" $k) -}}
{{- else if eq $k "service" -}}
{{- fail "config.DB_URL must not set service: a pg_service.conf entry would supply host, port and TLS settings the chart cannot check" -}}
{{- end -}}
{{- end -}}
{{- if ne (toJson $sslmode) (toJson (list "verify-full")) -}}
{{- fail (printf "config.DB_URL must use sslmode=verify-full, exactly once (with sslrootcert=%s)" $bundle) -}}
{{- end -}}
{{- if ne (toJson $rootcert) (toJson (list $bundle)) -}}
{{- fail (printf "config.DB_URL must set sslrootcert=%s, exactly once" $bundle) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "compliance.databaseCaFile" -}}
{{- printf "%s/%s" (trimSuffix "/" .Values.databaseCa.mountPath) .Values.databaseCa.key -}}
{{- end -}}
