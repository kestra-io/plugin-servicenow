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
- `AbstractServiceNow` shares authentication and bounded native API transport. Native runs cache OAuth tokens in their transport, not on the task.
- `ServiceNowRecords` resolves references, unwraps native fields and checks current model transitions while preserving negative state codes.
- `LoadImportSet` submits synchronous single rows, records every transform-map result and fails on configured statuses even inside HTTP 201. Input rows and transform results have different counts.
- `UpsertCI` uses IRE/query for identification and IRE for commits. Table API calls are read-only verification/reference lookups.

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
