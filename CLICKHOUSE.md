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

Run these in the SQL UI (`./gke-port-forward.sh clickhouse`, then `http://localhost:8123/play`,
user `default`) or with
`kubectl -n clickhouse exec -it ch-clickhouse-0-0-0 -- clickhouse-client --password "$PW"`.
They're all read-only except the `ALTER TABLE` statements under
[Storage and retention](#storage-and-retention). Replace `<trace id>` before running a query that
contains it.

**What the data looks like**
- **Everything here is API data.** No query needs a `message-service` filter for that, but the
  trace queries still filter on `ServiceName` so they read like OpenObserve's.
- **Traces** are sampled at 10% (`TRACES_SAMPLING_PROBABILITY` in `k8s/configmap.yaml`), so counts
  from traces are about a tenth of the real call volume.
  - `Duration` is in nanoseconds.
  - `SpanKind` is `Server`, `Client`, `Internal`, ...; `StatusCode` is `Ok`, `Error` or `Unset`.
  - gRPC server spans are named `<service>/<method>`, e.g. `message.v1.MessageService/CreateMessage`.
- **Logs** come from the pod's stdout, not from OTLP, which changes three things:
  - `ServiceName` and the `TraceId` column are empty.
  - `Body` is the app's Logstash JSON line; read its fields with `JSONExtractString(Body, ...)`.
    Trace ids are in `traceId` inside `Body`.
  - Every gRPC call writes one unsampled `rpc` line (`rpc_method`, `grpc_status`, `duration_ms`).
    That makes logs the exact source for call counts, error rates and latency.
- **Metrics** are Micrometer's: dotted names (`grpc.server` = Prometheus `grpc_server_milliseconds_*`,
  `grpc.errors` = `grpc_errors_total`, `jdbc.query`, `hikaricp.connections.active`,
  `jvm.memory.used`). Counters and histograms are cumulative since pod start, so a window's
  increase is the last value minus the first, per pod. Attribute keys are as Micrometer named
  them: `grpc.errors` uses `rpc_method` / `error_code`, while `grpc.server` uses Micrometer's
  gRPC convention, `rpc.method` / `grpc.status_code`. Run the discovery queries first to confirm
  names and keys against live data.

### Discover what's there

```sql
-- Rows, size and date range per table
SELECT table,
       sum(rows) AS rows,
       formatReadableSize(sum(data_compressed_bytes)) AS on_disk,
       min(partition) AS oldest_day,
       max(partition) AS newest_day
FROM system.parts
WHERE database = 'otel' AND active
GROUP BY table
ORDER BY table;

-- Metric names, units and attribute keys (last hour)
SELECT *
FROM
(
    SELECT 'sum' AS type, MetricName, any(MetricUnit) AS unit,
           groupUniqArrayArray(mapKeys(Attributes)) AS attribute_keys, count() AS points
    FROM otel.otel_metrics_sum WHERE TimeUnix > now() - INTERVAL 1 HOUR GROUP BY MetricName
    UNION ALL
    SELECT 'gauge', MetricName, any(MetricUnit),
           groupUniqArrayArray(mapKeys(Attributes)), count()
    FROM otel.otel_metrics_gauge WHERE TimeUnix > now() - INTERVAL 1 HOUR GROUP BY MetricName
    UNION ALL
    SELECT 'histogram', MetricName, any(MetricUnit),
           groupUniqArrayArray(mapKeys(Attributes)), count()
    FROM otel.otel_metrics_histogram WHERE TimeUnix > now() - INTERVAL 1 HOUR GROUP BY MetricName
)
ORDER BY type, MetricName;

-- Span names and their attribute keys (last hour)
SELECT SpanName, SpanKind, count() AS spans,
       groupUniqArrayArray(mapKeys(SpanAttributes)) AS attribute_keys
FROM otel.otel_traces
WHERE Timestamp > now() - INTERVAL 1 HOUR
GROUP BY SpanName, SpanKind
ORDER BY spans DESC;

-- JSON fields in the log lines (last hour)
SELECT arrayJoin(JSONExtractKeys(Body)) AS field, count() AS lines
FROM otel.otel_logs
WHERE TimestampTime > now() - INTERVAL 1 HOUR
GROUP BY field
ORDER BY lines DESC;
```

### API calls (exact, from the `rpc` log lines)

```sql
-- Calls, errors and latency per method (last hour)
SELECT JSONExtractString(Body, 'rpc_method') AS method,
       count() AS calls,
       countIf(JSONExtractString(Body, 'grpc_status') != 'OK') AS errors,
       round(errors / calls * 100, 2) AS error_pct,
       round(quantile(0.50)(JSONExtractFloat(Body, 'duration_ms')), 1) AS p50_ms,
       round(quantile(0.95)(JSONExtractFloat(Body, 'duration_ms')), 1) AS p95_ms,
       round(quantile(0.99)(JSONExtractFloat(Body, 'duration_ms')), 1) AS p99_ms
FROM otel.otel_logs
WHERE TimestampTime > now() - INTERVAL 1 HOUR
  AND JSONExtractString(Body, 'message') = 'rpc'
GROUP BY method
ORDER BY calls DESC;

-- Calls and errors per minute - e.g. to follow a k6 run
SELECT toStartOfMinute(TimestampTime) AS minute,
       count() AS calls,
       countIf(JSONExtractString(Body, 'grpc_status') != 'OK') AS errors,
       round(quantile(0.95)(JSONExtractFloat(Body, 'duration_ms')), 1) AS p95_ms
FROM otel.otel_logs
WHERE TimestampTime > now() - INTERVAL 1 HOUR
  AND JSONExtractString(Body, 'message') = 'rpc'
GROUP BY minute
ORDER BY minute;

-- gRPC status codes per method (last 24 hours)
SELECT JSONExtractString(Body, 'rpc_method') AS method,
       JSONExtractString(Body, 'grpc_status') AS status,
       count() AS calls
FROM otel.otel_logs
WHERE TimestampTime > now() - INTERVAL 24 HOUR
  AND JSONExtractString(Body, 'message') = 'rpc'
GROUP BY method, status
ORDER BY method, calls DESC;
```

### Traces

```sql
-- Latency per method from sampled server spans (last hour)
SELECT SpanName,
       count() AS sampled_calls,
       round(quantile(0.50)(Duration) / 1e6, 1) AS p50_ms,
       round(quantile(0.95)(Duration) / 1e6, 1) AS p95_ms,
       round(quantile(0.99)(Duration) / 1e6, 1) AS p99_ms,
       countIf(StatusCode = 'Error') AS errors
FROM otel.otel_traces
WHERE ServiceName = 'message-service'
  AND SpanKind = 'Server'
  AND Timestamp > now() - INTERVAL 1 HOUR
GROUP BY SpanName
ORDER BY p95_ms DESC;

-- The 20 slowest calls, with their trace ids (last hour)
SELECT Timestamp, TraceId, SpanName, round(Duration / 1e6, 1) AS ms, StatusCode
FROM otel.otel_traces
WHERE ServiceName = 'message-service'
  AND SpanKind = 'Server'
  AND Timestamp > now() - INTERVAL 1 HOUR
ORDER BY Duration DESC
LIMIT 20;

-- Where the time goes inside calls: SQL statements, cache operations, ... (last hour)
SELECT SpanName, SpanKind,
       count() AS spans,
       round(avg(Duration) / 1e6, 2) AS avg_ms,
       round(quantile(0.95)(Duration) / 1e6, 2) AS p95_ms,
       round(sum(Duration) / 1e9, 2) AS total_s
FROM otel.otel_traces
WHERE ServiceName = 'message-service'
  AND SpanKind != 'Server'
  AND Timestamp > now() - INTERVAL 1 HOUR
GROUP BY SpanName, SpanKind
ORDER BY total_s DESC;

-- Failed calls (last hour)
SELECT Timestamp, TraceId, SpanName, StatusMessage, SpanAttributes
FROM otel.otel_traces
WHERE ServiceName = 'message-service'
  AND SpanKind = 'Server'
  AND StatusCode = 'Error'
  AND Timestamp > now() - INTERVAL 1 HOUR
ORDER BY Timestamp DESC
LIMIT 50;

-- Every span of one trace, in order (paste a TraceId from the queries above)
SELECT Timestamp, SpanId, ParentSpanId, SpanKind, SpanName,
       round(Duration / 1e6, 2) AS ms, StatusCode, SpanAttributes
FROM otel.otel_traces
WHERE TraceId = '<trace id>'
ORDER BY Timestamp;
```

### Logs

```sql
-- Log volume by level, 5-minute buckets (last 6 hours)
SELECT toStartOfFiveMinutes(TimestampTime) AS t,
       JSONExtractString(Body, 'level') AS level,
       count() AS lines
FROM otel.otel_logs
WHERE TimestampTime > now() - INTERVAL 6 HOUR
GROUP BY t, level
ORDER BY t, level;

-- Latest warnings and errors
SELECT Timestamp,
       ResourceAttributes['k8s.pod.name'] AS pod,
       JSONExtractString(Body, 'level') AS level,
       JSONExtractString(Body, 'logger_name') AS logger,
       JSONExtractString(Body, 'message') AS message,
       JSONExtractString(Body, 'stack_trace') AS stack_trace
FROM otel.otel_logs
WHERE JSONExtractString(Body, 'level') IN ('WARN', 'ERROR')
ORDER BY Timestamp DESC
LIMIT 50;

-- Full-text search in messages (the Body column has a token index)
SELECT Timestamp, JSONExtractString(Body, 'message') AS message
FROM otel.otel_logs
WHERE hasToken(Body, 'Hazelcast')
  AND TimestampTime > now() - INTERVAL 24 HOUR
ORDER BY Timestamp DESC
LIMIT 50;

-- Log lines of one trace
SELECT Timestamp,
       JSONExtractString(Body, 'level') AS level,
       JSONExtractString(Body, 'message') AS message,
       Body
FROM otel.otel_logs
WHERE JSONExtractString(Body, 'traceId') = '<trace id>'
ORDER BY Timestamp;
```

### Traces and logs together

```sql
-- Failed calls with the log lines written during them (last hour). Only sampled traces have
-- spans, so this covers about 10% of failures; the `rpc` log queries above cover all of them.
SELECT t.Timestamp, t.SpanName, t.StatusMessage,
       l.level, l.message
FROM
(
    SELECT TraceId, Timestamp, SpanName, StatusMessage
    FROM otel.otel_traces
    WHERE ServiceName = 'message-service'
      AND SpanKind = 'Server'
      AND StatusCode = 'Error'
      AND Timestamp > now() - INTERVAL 1 HOUR
) AS t
LEFT JOIN
(
    SELECT JSONExtractString(Body, 'traceId') AS TraceId,
           JSONExtractString(Body, 'level') AS level,
           JSONExtractString(Body, 'message') AS message
    FROM otel.otel_logs
    WHERE TimestampTime > now() - INTERVAL 1 HOUR
      AND JSONExtractString(Body, 'traceId') != ''
) AS l USING (TraceId)
ORDER BY t.Timestamp DESC
LIMIT 100;
```

### Metrics

```sql
-- gRPC errors by method and error code (last hour). Increase per pod = last value - first
-- value; a series that first appears inside the window is undercounted by its first point.
SELECT Attributes['rpc_method'] AS method,
       Attributes['error_code'] AS error_code,
       sum(increase) AS errors
FROM
(
    SELECT Attributes, ResourceAttributes['k8s.pod.name'] AS pod, StartTimeUnix,
           max(Value) - min(Value) AS increase
    FROM otel.otel_metrics_sum
    WHERE MetricName = 'grpc.errors' AND TimeUnix > now() - INTERVAL 1 HOUR
    GROUP BY Attributes, pod, StartTimeUnix
)
GROUP BY method, error_code
ORDER BY errors DESC;

-- Calls and average latency per method from the grpc.server histogram (last hour)
SELECT Attributes['rpc.method'] AS method,
       sum(calls) AS total_calls,
       round(sum(total_ms) / total_calls, 2) AS avg_ms
FROM
(
    SELECT Attributes, ResourceAttributes['k8s.pod.name'] AS pod, StartTimeUnix,
           max(Count) - min(Count) AS calls,
           max(Sum) - min(Sum) AS total_ms
    FROM otel.otel_metrics_histogram
    WHERE MetricName = 'grpc.server' AND TimeUnix > now() - INTERVAL 1 HOUR
    GROUP BY Attributes, pod, StartTimeUnix
)
GROUP BY method
HAVING total_calls > 0
ORDER BY avg_ms DESC;

-- p95 per method from the grpc.server buckets (last hour). The result is the upper bound of the
-- bucket holding the 95th percentile (5ms, 10ms, 25ms, ... - see the SLO buckets in
-- application.yaml), the same resolution histogram_quantile has in Grafana.
SELECT method,
       arraySum(counts) AS calls,
       arrayFirstIndex(c -> c >= 0.95 * calls, arrayCumSum(counts)) AS idx,
       if(idx > length(bounds), inf, bounds[idx]) AS p95_le_ms
FROM
(
    SELECT method, any(b) AS bounds, sumForEach(delta) AS counts
    FROM
    (
        SELECT Attributes['rpc.method'] AS method,
               any(ExplicitBounds) AS b,
               arrayMap((last, first) -> last - first,
                        argMax(BucketCounts, TimeUnix),
                        argMin(BucketCounts, TimeUnix)) AS delta
        FROM otel.otel_metrics_histogram
        WHERE MetricName = 'grpc.server' AND TimeUnix > now() - INTERVAL 1 HOUR
        GROUP BY Attributes, ResourceAttributes['k8s.pod.name'], StartTimeUnix
    )
    GROUP BY method
)
WHERE calls > 0
ORDER BY p95_le_ms DESC;

-- Active DB connections per pod, per minute (last hour)
SELECT toStartOfMinute(TimeUnix) AS minute,
       ResourceAttributes['k8s.pod.name'] AS pod,
       max(Value) AS active_connections
FROM otel.otel_metrics_gauge
WHERE MetricName = 'hikaricp.connections.active' AND TimeUnix > now() - INTERVAL 1 HOUR
GROUP BY minute, pod
ORDER BY minute, pod;

-- JVM heap used per pod, per minute (last hour)
SELECT minute, pod, round(max(heap_bytes) / 1048576) AS heap_mb
FROM
(
    SELECT toStartOfMinute(TimeUnix) AS minute,
           ResourceAttributes['k8s.pod.name'] AS pod,
           TimeUnix,
           sum(Value) AS heap_bytes
    FROM otel.otel_metrics_gauge
    WHERE MetricName = 'jvm.memory.used'
      AND Attributes['area'] = 'heap'
      AND TimeUnix > now() - INTERVAL 1 HOUR
    GROUP BY minute, pod, TimeUnix
)
GROUP BY minute, pod
ORDER BY minute, pod;
```

### Storage and retention

```sql
-- Disk use and compression per table
SELECT table,
       formatReadableSize(sum(data_compressed_bytes)) AS compressed,
       formatReadableSize(sum(data_uncompressed_bytes)) AS uncompressed,
       round(sum(data_uncompressed_bytes) / sum(data_compressed_bytes), 1) AS ratio
FROM system.parts
WHERE database = 'otel' AND active
GROUP BY table
ORDER BY sum(data_compressed_bytes) DESC;

-- Free space on the data volume (10Gi PVC)
SELECT name, path,
       formatReadableSize(free_space) AS free,
       formatReadableSize(total_space) AS total
FROM system.disks;

-- Keep 7 days instead of 3 (the exporter only sets TTL when it creates a table). Check
-- free space first; the PVC is 10Gi.
ALTER TABLE otel.otel_traces MODIFY TTL toDate(Timestamp) + INTERVAL 7 DAY;
ALTER TABLE otel.otel_logs MODIFY TTL TimestampTime + INTERVAL 7 DAY;
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
