# Helm Values Reference

Install KubeFn with Helm:

```bash
helm repo add kubefn https://charts.kubefn.com
helm install my-organism kubefn/kubefn -f values.yaml
```

## All Values

| Key | Type | Default | Description |
|---|---|---|---|
| `runtime.image.repository` | string | `kubefn/runtime` | Container image repository |
| `runtime.image.tag` | string | `latest` | Container image tag |
| `runtime.image.pullPolicy` | string | `IfNotPresent` | Image pull policy |
| `runtime.replicas` | int | `1` | Number of organism replicas |
| `runtime.resources.requests.cpu` | string | `500m` | CPU request |
| `runtime.resources.requests.memory` | string | `512Mi` | Memory request |
| `runtime.resources.limits.cpu` | string | `2` | CPU limit |
| `runtime.resources.limits.memory` | string | `2Gi` | Memory limit |
| `runtime.jvmArgs` | list | `["-Xmx1536m", "-XX:+UseZGC"]` | JVM arguments passed to the runtime |
| `runtime.port` | int | `8080` | Function HTTP port |
| `runtime.adminPort` | int | `8081` | Admin API port |
| `runtime.functionsDir` | string | `/app/functions` | Directory for function JARs inside the container |
| `runtime.env` | list | `[]` | Additional environment variables |
| `operator.enabled` | bool | `true` | Deploy the KubeFn operator (manages CRDs, auto-scaling) |
| `operator.image.repository` | string | `kubefn/operator` | Operator image repository |
| `operator.image.tag` | string | `latest` | Operator image tag |
| `ingress.enabled` | bool | `false` | Create an Ingress resource |
| `ingress.className` | string | `""` | Ingress class name |
| `ingress.host` | string | `""` | Ingress hostname |
| `ingress.tls.enabled` | bool | `false` | Enable TLS on ingress |
| `ingress.tls.secretName` | string | `""` | TLS secret name |
| `serviceAccount.create` | bool | `true` | Create a service account |
| `serviceAccount.name` | string | `""` | Service account name (auto-generated if empty) |
| `prometheus.enabled` | bool | `true` | Expose Prometheus metrics endpoint |
| `prometheus.serviceMonitor` | bool | `false` | Create a Prometheus ServiceMonitor CRD |

## Example values.yaml

```yaml
runtime:
  image:
    repository: kubefn/runtime
    tag: "0.4.0"
  replicas: 2
  resources:
    requests:
      cpu: "1"
      memory: "1Gi"
    limits:
      cpu: "4"
      memory: "4Gi"
  jvmArgs:
    - "-Xmx3g"
    - "-XX:+UseZGC"
    - "-XX:+ZGenerational"
  env:
    - name: KUBEFN_LOG_LEVEL
      value: "INFO"

operator:
  enabled: true

ingress:
  enabled: true
  className: nginx
  host: api.example.com
  tls:
    enabled: true
    secretName: api-tls

prometheus:
  enabled: true
  serviceMonitor: true
```

## Resource Sizing Guide

| Workload | Functions | Replicas | Memory | CPU | JVM Heap |
|---|---|---|---|---|---|
| Dev/test | 1-10 | 1 | 512Mi | 500m | 384m |
| Small production | 10-50 | 2 | 2Gi | 2 | 1536m |
| Large production | 50-200 | 3+ | 4Gi | 4 | 3g |
| High-throughput | 50-200 | 3+ | 8Gi | 8 | 6g |

Set JVM heap (`-Xmx`) to 75% of the memory limit.
