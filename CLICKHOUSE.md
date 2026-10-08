# ClickHouse

A minimal ClickHouse for the dev cluster, holding only the API's telemetry (OTLP metrics and
traces from `message-service`, plus that container's logs), next to the existing OpenObserve
backend. Nothing is removed from the OpenObserve/Prometheus path.

```
message-service ──OTLP──> otel-collector ─┬─> Prometheus exporter / OpenObserve   (unchanged)
                                          └─> otlp/clickhouse ─┐
pod logs (default ns) ──> log-collector ──┬─> OpenObserve      │ (filter: container
                                          └─> otlp/clickhouse ─┤  message-service only)
                                                               v
                          clickhouse ns:  otel-collector-clickhouse ──native :9000──> ClickHouse (ch)
                                                                                         │
                                                                                     Keeper (ch)
```

| Piece | Where | Notes |
| --- | --- | --- |
| Operator | `k8s/argocd/apps/clickhouse-operator.yaml`, `k8s/clickhouse-operator/values.yaml` | chart `oci://ghcr.io/clickhouse/clickhouse-operator-helm` 0.0.8, namespace `clickhouse-operator-system`, wave -1. Webhooks off, so cert-manager isn't needed. Watches only `clickhouse`. |
| ClickHouse + Keeper | `k8s/clickhouse/{clickhousecluster,keepercluster}.yaml` | 26.3.45.3 (LTS), 1 replica each. ClickHouse: 250m/1Gi requests, 1 CPU/2Gi limits, 10Gi PVC. Keeper: 100m/256Mi, 1Gi PVC. Wave 0. |
| Writer | `k8s/clickhouse/otel-collector-clickhouse.yaml` + `config/` | The only ClickHouse client. Creates the `otel` database and tables on startup, with a 72h TTL. |
| Password | `k8s/clickhouse/externalsecret.yaml` | Secret Manager `clickhouse-default-password` (seeded by `gke-secrets-seed.sh` from 1Password `CLICKHOUSE_DEFAULT_PASSWORD`). |
| Network | `k8s/clickhouse/networkpolicy.yaml` | Default deny ingress. Allowed: the observability collectors to the writer on 4317, and the writer to ClickHouse on 9000. The operator manages its own operator, Keeper and interserver policies. |

**Why a separate writer collector:** the `clickhouse` exporter runs its schema DDL when it
starts, and a failed start stops the whole collector. Inside `otel-collector` or `log-collector`,
a ClickHouse outage would also stop the OpenObserve pipelines. Here only the writer is affected;
upstream, the `otlp/clickhouse` exporters queue, retry and then drop.

## Before the first sync

1. Create the password in 1Password (`dev-vault/k8s-clickhouse`, field `password`; the path is
   in `.env.example`), then seed it:
   `op run --env-file=.env -- env PROJECT_ID=k8s-dev-412419 ./gke-secrets-seed.sh`

## Bring-up checks (in order)

```sh
# 1. Operator running, CRDs installed
kubectl -n argocd get applications clickhouse-operator clickhouse
kubectl -n clickhouse-operator-system get pods
kubectl get crd clickhouseclusters.clickhouse.com keeperclusters.clickhouse.com

# 2. Password Secret built by External Secrets
kubectl -n clickhouse get externalsecret,secret clickhouse-default-password

# 3. Keeper, then ClickHouse (the operator runs a short version-probe Job first)
kubectl -n clickhouse get keepercluster,clickhousecluster
kubectl -n clickhouse get pods,pvc,svc --show-labels
kubectl -n clickhouse describe clickhousecluster ch | sed -n '/Status:/,$p'
kubectl -n clickhouse-operator-system logs deploy/clickhouse-operator-controller-manager --tail=100

# 4. Writer connected and schema created
kubectl -n clickhouse logs deploy/otel-collector-clickhouse -c create-otel-database
kubectl -n clickhouse logs deploy/otel-collector-clickhouse --tail=50

# 5. Data arriving (generate traffic first, e.g. a k6 script)
PW=$(gcloud secrets versions access latest --secret=clickhouse-default-password)
kubectl -n clickhouse exec -it ch-clickhouse-0-0-0 -- clickhouse-client --password "$PW" -q "
  SELECT 'traces', count() FROM otel.otel_traces UNION ALL
  SELECT 'logs', count() FROM otel.otel_logs UNION ALL
  SELECT 'metrics_sum', count() FROM otel.otel_metrics_sum UNION ALL
  SELECT 'metrics_histogram', count() FROM otel.otel_metrics_histogram"
```

The pod is named after the StatefulSet `<cluster>-clickhouse-<shard>-<replica>`, so
`ch-clickhouse-0-0-0`. Or skip `exec` and use the SQL UI: `./gke-port-forward.sh clickhouse`,
then open `http://localhost:8123/play`.

## Example queries

```sql
-- Slowest API calls in the last hour
SELECT SpanName, quantile(0.95)(Duration) / 1e6 AS p95_ms, count() AS calls
FROM otel.otel_traces
WHERE ServiceName = 'message-service' AND Timestamp > now() - INTERVAL 1 HOUR AND SpanKind = 'Server'
GROUP BY SpanName ORDER BY p95_ms DESC;

-- Errors in the API's logs
SELECT Timestamp, Body FROM otel.otel_logs
WHERE Body ILIKE '%ERROR%' ORDER BY Timestamp DESC LIMIT 50;

-- Which metrics are flowing
SELECT MetricName, count() FROM otel.otel_metrics_sum GROUP BY MetricName ORDER BY 2 DESC;
```

## Likely failure points

- **ClickHouse pod never created.** Check the operator logs. Without the webhook, an invalid spec
  only shows up there and in the CR's status conditions, not as a rejected apply.
- **ResourceQuota rejects a pod.** Run `kubectl -n clickhouse describe quota`. Pods or Jobs the
  operator creates show `FailedCreate` events on their StatefulSet or Job.
- **Writer stuck in `create-otel-database`.** The headless Service only resolves once the
  ClickHouse pod is Ready.
- **Writer CrashLoops after the init container.** The DDL failed. Check for a wrong password, or
  a NetworkPolicy blocking port 9000 (check the `clickhouse-clients` policy's pod selector against
  the real pod labels).
- **No logs in ClickHouse.** The log filter matches `k8s.container.name == "message-service"`;
  check `log-collector` logs for exporter errors.
- **Port-forward to `ch-clickhouse-headless` fails.** Port-forward straight to the pod:
  `kubectl -n clickhouse port-forward pod/<pod> 8123`.

## Not done (later)

- A dedicated ingest user instead of `default`.
- Prometheus scraping ClickHouse's own metrics (`:9363`) and the operator's (`:8080`).
- A ClickHouse datasource in Grafana.
- HA: 3 Keeper nodes and 2 ClickHouse replicas (raise `resourcequota.yaml` first).
