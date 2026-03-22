{{/*
Expand the name of the chart.
*/}}
{{- define "kubefn.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "kubefn.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Chart label value.
*/}}
{{- define "kubefn.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels applied to every resource.
*/}}
{{- define "kubefn.labels" -}}
helm.sh/chart: {{ include "kubefn.chart" . }}
{{ include "kubefn.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: kubefn
{{- end }}

{{/*
Selector labels (used in matchLabels and service selectors).
*/}}
{{- define "kubefn.selectorLabels" -}}
app.kubernetes.io/name: {{ include "kubefn.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Runtime selector labels.
*/}}
{{- define "kubefn.runtime.selectorLabels" -}}
{{ include "kubefn.selectorLabels" . }}
app.kubernetes.io/component: runtime
{{- end }}

{{/*
Operator selector labels.
*/}}
{{- define "kubefn.operator.selectorLabels" -}}
{{ include "kubefn.selectorLabels" . }}
app.kubernetes.io/component: operator
{{- end }}

{{/*
Runtime image reference.
*/}}
{{- define "kubefn.runtime.image" -}}
{{- printf "%s:%s" .Values.runtime.image.repository (.Values.runtime.image.tag | default .Chart.AppVersion) }}
{{- end }}

{{/*
Operator image reference.
*/}}
{{- define "kubefn.operator.image" -}}
{{- printf "%s:%s" .Values.operator.image.repository (.Values.operator.image.tag | default .Chart.AppVersion) }}
{{- end }}

{{/*
Target namespace — uses values.namespace, falling back to release namespace.
*/}}
{{- define "kubefn.namespace" -}}
{{- default .Release.Namespace .Values.namespace }}
{{- end }}
