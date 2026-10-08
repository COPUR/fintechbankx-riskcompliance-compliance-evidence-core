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
Aurora TLS (cicd-templates 4f0f266): a PostgreSQL DB_URL must verify the server
certificate and host name; sslmode=require encrypts but trusts any certificate.
The app and Flyway (migration owner) share this one URL.
*/}}
{{- define "compliance.validateDatabaseTls" -}}
{{- $url := toString (default "" (index .Values.config "DB_URL")) -}}
{{- if and (hasPrefix "jdbc:postgresql:" $url) (not (contains "sslmode=verify-full" $url)) -}}
{{- fail "config.DB_URL must use sslmode=verify-full (with sslrootcert=<databaseCa.mountPath>/<databaseCa.key>)" -}}
{{- end -}}
{{- end -}}

{{- define "compliance.databaseCaFile" -}}
{{- printf "%s/%s" (trimSuffix "/" .Values.databaseCa.mountPath) .Values.databaseCa.key -}}
{{- end -}}
