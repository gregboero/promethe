---
name: tracy-ai-observability
description: "Profile and observe JVM/native applications with JetBrains Tracy profiler."
source: BUNDLED
requires_cli: tracy
platforms: jvm
---

# Tracy Profiler — Promethe Observability Guide

[Tracy](https://github.com/JetBrains/tracy) is a real-time, nanosecond-resolution profiler for C/C++ and JVM applications, maintained by JetBrains.

## When to Use

- Profiling Promethe's own JVM performance (hot paths, GC pressure)
- Diagnosing latency spikes in LLM tool call chains
- Visualizing thread contention in coroutine-heavy Kotlin code
- Tracing memory allocations in gateway/shared modules
- Any performance investigation where standard logging isn't granular enough

## Setup

### JVM Integration
```
execute_command(command="gradle", args=["dependencies", "--configuration", "runtimeClasspath"])
```

Add Tracy JVM agent:
```kotlin
// build.gradle.kts
dependencies {
    runtimeOnly("com.github.nickallendev:tracy-java:0.1.0")
}
```

### Native Build
```
execute_command(command="git", args=["clone", "https://github.com/JetBrains/tracy.git"])
execute_command(command="cmake", args=["-B", "build", "-S", "tracy", "-DCMAKE_BUILD_TYPE=Release"])
execute_command(command="cmake", args=["--build", "build", "--target", "TracyProfiler"])
```

## Usage Patterns

### Zone Marking (Kotlin)
```kotlin
import tracy.Tracy

fun processToolCall(call: ToolCall) {
    Tracy.zone("processToolCall") {
        // ... profiled code
        Tracy.zone("llmInference") {
            val response = llmClient.complete(call.prompt)
        }
        Tracy.zone("toolExecution") {
            val result = executor.execute(call)
        }
    }
}
```

### Capture & Analyze
```
# Start Tracy profiler GUI
execute_command(command="tracy-profiler", args=[])

# Or capture to file
execute_command(command="tracy-capture", args=["-o", "profile.tracy", "-s", "5"])
```

## Key Features

| Feature | Description |
|---------|-------------|
| **Zones** | Named code regions with timing |
| **Plots** | Real-time value graphs (memory, queue depth) |
| **Messages** | Log messages correlated with timeline |
| **Memory** | Allocation tracking with callstacks |
| **Locks** | Mutex/lock contention visualization |
| **GPU** | GPU operation profiling |
