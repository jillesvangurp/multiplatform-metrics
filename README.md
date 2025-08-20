# Multiplatform Metrics

Kotlin multiplatform metrics registry with counters, gauges, timers and summaries.

Note this is a work in progress and the API may evolve.

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

## Multi platform

This is a Kotlin multi platform library that should work on all kotlin platforms (jvm, js, wasm, native, ios, android, etc).

