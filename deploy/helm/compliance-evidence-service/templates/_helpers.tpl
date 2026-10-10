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
so sslmode and sslrootcert must each appear exactly once; a custom sslfactory,
sslhostnameverifier or sslpasswordcallback would replace the verification.
Values are compared undecoded (PgJDBC decodes values, not names), so an encoded
value fails closed. The
ConfigMap exports every config key, so a second URL there (SPRING_DATASOURCE_*URL,
SPRING_FLYWAY_URL, SPRING_APPLICATION_JSON) would override DB_URL and is refused
too, and so is a config location or import (SPRING_CONFIG_IMPORT,
SPRING_CONFIG_ADDITIONAL_LOCATION, SPRING_CONFIG_LOCATION, also indexed), which
loads a file or configtree that can set the URL. Keys are compared in the
spelling Spring's relaxed binding reads from the environment: upper case, "."
and "-" as "_", and ADDITIONALLOCATION as well as ADDITIONAL_LOCATION. The app and the
Flyway migration Job (schema owner) share DB_URL. The application's
DatabaseTlsGuard repeats the URL checks at startup, in the Job too.
*/}}
{{- define "compliance.validateDatabaseTls" -}}
{{- $bundle := "/etc/fintechbankx/rds-ca/global-bundle.pem" -}}
{{- range $key, $_ := .Values.config -}}
{{- $name := upper (replace "-" "_" (replace "." "_" (toString $key))) -}}
{{- if regexMatch "^SPRING_(DATASOURCE_.*URL|DATASOURCE_HIKARI_DATA_?SOURCE_?PROPERTIES.*|FLYWAY_URL|APPLICATION_JSON)$" $name -}}
{{- fail (printf "config.%s must not be set: config.DB_URL is the only database URL (sslmode=verify-full)" $key) -}}
{{- end -}}
{{- if regexMatch "^SPRING_CONFIG_(IMPORT|ADDITIONAL_?LOCATION|LOCATION)([_\\[].*)?$" $name -}}
{{- fail (printf "config.%s must not be set: it loads configuration that can override config.DB_URL" $key) -}}
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
{{- range $param := splitList "&" $query -}}
{{- $kv := splitn "=" 2 $param -}}
{{- $k := $kv._0 -}}
{{- $v := toString (default "" $kv._1) -}}
{{- if eq $k "sslmode" -}}
{{- $sslmode = append $sslmode $v -}}
{{- else if eq $k "sslrootcert" -}}
{{- $rootcert = append $rootcert $v -}}
{{- else if has $k (list "sslfactory" "sslhostnameverifier" "sslpasswordcallback") -}}
{{- fail (printf "config.DB_URL must not set %s: it replaces certificate or host name verification" $k) -}}
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
