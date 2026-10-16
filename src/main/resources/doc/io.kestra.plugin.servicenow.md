# How to use the ServiceNow plugin

Read and write records in any ServiceNow table from Kestra flows.

## Authentication

Set `domain` to your ServiceNow instance subdomain (no protocol, e.g. `mycompany` for `https://mycompany.service-now.com/`) or `uri` to override the full base URL (e.g. for custom domains or test instances), along with `username` and `password` for basic auth. For OAuth, also set `clientId` and `clientSecret` — the plugin will exchange credentials for a bearer token automatically. Store secrets in [secrets](https://kestra.io/docs/concepts/secret) and set connection properties on each task.

## Tasks

`Get` queries records from a ServiceNow table — set `table` to the API table name (e.g. `incident`). Filter results with `query` (ServiceNow encoded query syntax), scope columns with `fields`, and paginate with `limit` and `offset`. Control result handling with `fetchType`: `FETCH` (default), `FETCH_ONE`, or `STORE`.

`Post` creates a record in a `table` — set `data` as a map of field names to values.

`CreateIncident` creates an `incident` from typed `shortDescription`, `incidentDescription`, `urgency` and `impact`, plus any extra fields in `data`.

`Update` updates a record by `sysId` in a `table` — set `data` with the fields to change.

`Delete` removes a record by `sysId` from a `table`.
