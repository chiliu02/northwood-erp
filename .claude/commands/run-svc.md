---
description: Show the command to start one service with SPRING_PROFILES_ACTIVE=kafka
argument-hint: <service> (one of product / sales / inventory / manufacturing / purchasing / finance / reporting)
---

Print the exact PowerShell command the user should run in a new terminal to start `$ARGUMENTS-service` with the kafka profile set. Do NOT run `mvn spring-boot:run` yourself — it's a long-running foreground process that would block the agent.

Expected output shape:

```
Start $ARGUMENTS-service:

  $env:SPRING_PROFILES_ACTIVE = "kafka"
  mvn -pl $ARGUMENTS-service spring-boot:run

Wait for: "Started <ServiceName>Application" (typically ~10-15s).
Port: <port from the table below>
```

Service → port table (from `docs/demo-script.md`):
- product → 8081
- sales → 8082
- inventory → 8083
- manufacturing → 8084
- purchasing → 8085
- finance → 8086
- reporting → 8087

Notes to include in the response:
- The kafka profile is required for the cross-service event bus. Without it, services run on the in-JVM bus and never see events from other services.
- If port is already in use, an instance is already running — `Get-Process java` to find it.
- The BFFs (`demo-web-ui-bff` :8080, `erp-web-ui-bff` :8089) do **not** need the kafka profile, except for `demo-web-ui-bff` which needs it for the event-stream aggregator (`/api/events` SSE). Document this if the user asks for one of the BFFs.

If `$ARGUMENTS` is empty or doesn't match a known service, ask which service they want.
