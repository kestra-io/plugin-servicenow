# Kestra ServiceNow Plugin

## What

- Provides plugin components under `io.kestra.plugin.servicenow`.
- Includes classes such as `Delete`, `Update`, `Post`, `Get`.

## Why

- This plugin integrates Kestra with ServiceNow.
- It provides tasks that create, update, read, and delete ServiceNow records via REST.

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
