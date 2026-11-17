# Kestra ServiceNow Plugin

## What

- Provides plugin components under `io.kestra.plugin.servicenow`.
- Includes classes such as `Delete`, `Update`, `Post`, `Get`.
- Native operations: `CreateChange`, `WaitForApproval`, `TransitionChange`, `LoadImportSet` and `UpsertCI`.

## Why

- What user problem does this solve? Teams need to create, update, read, and delete ServiceNow records via REST from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps ServiceNow steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on ServiceNow.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `servicenow`

### Key Plugin Classes

- `io.kestra.plugin.servicenow.Get`
- `io.kestra.plugin.servicenow.Post`
- `AbstractServiceNow` shares stateless authentication and bounded native API transport. Never keep a client/token on the task.
- `ServiceNowRecords` resolves references, unwraps native fields and checks current model transitions. Negative state codes must keep their sign.
- `LoadImportSet` submits synchronous single rows, records every transform-map result and fails on configured statuses even inside HTTP 201. Input rows and transform results have different counts.
- `UpsertCI` uses IRE/query for identification and IRE for commits. Table API calls are read-only verification/reference lookups.

### Native task rules

- Preserve the existing flat task namespace; do not move old task types into new packages.
- Follow the issue amendment: Change Management REST API for changes, Import Sets first for customer ingestion and explicit IRE for registered sources. No direct CMDB Table API write fallback.
- Use core `Data` for inline/file input and core HTTP/Jackson. Native responses are capped before parsing; write retries and redirects are disabled.
- Request state waypoints explicitly and resolve them through fresh nextstates. Never construct an unseen model graph or choose cancellation to reach Closed.
- Re-read state/revision before transitions and verify the result. A timeout or rejected approval must not fall through as success.
- Approval audit timestamps are record updates, not guaranteed decision-event timestamps. Keep historical audit rows distinct from the global approval decision.
- Every secret field needs `@ToString.Exclude`. Lifecycle handles also need JSON/string/equality exclusions and must be cleared after each run.
- Run `./verify.sh`. Every task needs an unconditional happy-path test plus relevant partial/error/cancellation controls. Report live-versus-fixture coverage accurately.

### Project Structure

```
plugin-servicenow/
├── src/main/java/io/kestra/plugin/servicenow/
├── src/test/java/io/kestra/plugin/servicenow/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
