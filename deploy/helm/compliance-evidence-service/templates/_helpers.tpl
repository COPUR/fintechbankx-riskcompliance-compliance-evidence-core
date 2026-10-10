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
SPRING_FLYWAY_URL, SPRING_APPLICATION_JSON, in any spelling Spring's relaxed
binding accepts) would override DB_URL and is refused too. The app and Flyway
(migration owner) share DB_URL. The application's DatabaseTlsGuard repeats the
URL checks at startup.
*/}}
{{- define "compliance.validateDatabaseTls" -}}
{{- $bundle := "/etc/fintechbankx/rds-ca/global-bundle.pem" -}}
{{- range $key, $_ := .Values.config -}}
{{- $name := upper (replace "-" "_" (replace "." "_" (toString $key))) -}}
{{- if regexMatch "^SPRING_(DATASOURCE_.*URL|DATASOURCE_HIKARI_DATA_?SOURCE_?PROPERTIES.*|FLYWAY_URL|APPLICATION_JSON)$" $name -}}
{{- fail (printf "config.%s must not be set: config.DB_URL is the only database URL (sslmode=verify-full)" $key) -}}
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
