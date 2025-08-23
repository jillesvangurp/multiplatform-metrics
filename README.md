# Multiplatform Metrics

Kotlin multiplatform metrics registry with counters, gauges, timers and summaries. Timers support percentiles and SLA boundaries. On JVM, you can publish to a micrometer MeterRegistry (like comes with Spring).

Intended to have a more Kotlin native feel to it. Which means DSLs, using durations and building on other stuff in `kotlin.time` package.

**Note** this is a work in progress and the API may evolve.

## Examples

### Simple meter registry

```kotlin
val registry = SimpleMeterRegistry()
val counter = registry.counter("hits")
counter.inc()
println(registry.snapshot().toJson(pretty = true))
```

Produces

```text
{
  "points": [
    {
      "type": "counter",
      "name": "hits",
      "tags": {},
      "count": 1
    }
  ]
}
```

### Micrometer on the JVM

```kotlin
val micrometer = io.micrometer.core.instrument.simple.SimpleMeterRegistry()
val registry = MicrometerMeterRegistry(micrometer)
val gauge = registry.gauge("temp")
gauge.set(12.3)
println(micrometer.get("temp").gauge().value())
```

Produces

```text
12.3
```

### Timer percentiles and SLA

```kotlin
val registry = SimpleMeterRegistry()
val timer = registry.timer(
  "latency",
  config = TimerConfig(percentiles = listOf(0.5), sla = listOf(50.milliseconds))
)
listOf(10, 50, 100).forEach { timer.record(it.milliseconds) }
println(registry.snapshot().toJson(pretty = true))
```

Produces

```text
{
  "points": [
    {
      "type": "timer",
      "name": "latency",
      "tags": {},
      "count": 3,
      "sum": 160.0,
      "min": 10.0,
      "max": 100.0
    },
    {
      "type": "timer",
      "name": "latency",
      "tags": {
        "percentile": "0.5"
      },
      "value": 50.0
    },
    {
      "type": "timer",
      "name": "latency",
      "tags": {
        "sla": "50"
      },
      "count": 2
    }
  ]
}
```

### OpenTelemetry JSON lines

```kotlin
val registry = SimpleMeterRegistry()
registry.counter("hits").inc()
val line = registry.snapshot().toOpenTelemetryJsonLines().first()
println((line.take(77) + "...").trim())
```

Produces

```text
{"resourceMetrics":[{"scopeMetrics":[{"metrics":[{"name":"hits","sum":{"aggre...
```

### Prometheus lines

```kotlin
val registry = SimpleMeterRegistry()
registry.counter("hits").inc()
println(registry.snapshot().toPrometheusLines().joinToString("\n"))
```

Produces

```text
hits 1
```

## Multi platform

This is a Kotlin multi platform library that should work on all kotlin platforms (jvm, js, wasm, native, ios, android, etc).

## Vibe Coding

**Disclaimer** This project was mostly generated using codex and gpt 5. I'm planning to actually use this and all seems fine so far.
But some people might like to know where this came from. Of course knowing what to ask for helps and I have quite a bit of
experience with the topic of observability. I just did not feel like reinventing this wheel manually was a good idea.

