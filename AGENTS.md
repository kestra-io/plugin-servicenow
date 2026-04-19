# Kestra ServiceNow Plugin

## What

- Provides plugin components under `io.kestra.plugin.servicenow`.
- Includes classes such as `Delete`, `Update`, `Post`, `Get`.

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
