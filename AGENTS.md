# Kestra ServiceNow Plugin

## What

Automate ServiceNow tasks with Kestra data orchestration. Exposes 2 plugin components (tasks, triggers, and/or conditions).

## Why

Enables Kestra workflows to interact with ServiceNow, allowing orchestration of ServiceNow-based operations as part of data pipelines and automation workflows.

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

### Important Commands

```bash
# Build the plugin
./gradlew shadowJar

# Run tests
./gradlew test

# Build without tests
./gradlew shadowJar -x test
```

### Configuration

All tasks and triggers accept standard Kestra plugin properties. Credentials should use
`{{ secret('SECRET_NAME') }}` — never hardcode real values.

## Agents

**IMPORTANT:** This is a Kestra plugin repository (prefixed by `plugin-`, `storage-`, or `secret-`). You **MUST** delegate all coding tasks to the `kestra-plugin-developer` agent. Do NOT implement code changes directly — always use this agent.
