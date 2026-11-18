# How to use the ServiceNow plugin

Read and write ServiceNow records, request change approval, load inventory through transform maps and reconcile configuration items from Kestra flows.

## Authentication

Set `domain` to your ServiceNow instance subdomain (no protocol, e.g. `mycompany` for `https://mycompany.service-now.com/`) or `uri` to override the full base URL, along with `username` and `password` for basic auth. For OAuth, also set `clientId` and `clientSecret`. Native tasks reuse one bearer token per run and refresh it once if a request returns HTTP 401; a second 401 fails that request. Tokens are never stored on the task. Existing Table API tasks still exchange credentials per request. Store credentials in [secrets](https://kestra.io/docs/concepts/secret).

## Tasks

`Get` queries records from a ServiceNow table. Set `table` to its API name, filter with `query`, choose `fields` and paginate with `limit` and `offset`. Control result handling with `fetchType`: `FETCH`, `FETCH_ONE` or `STORE`.

`Post` creates a record in a `table` from a `data` map.

`CreateIncident` creates an `incident` from typed `shortDescription`, `incidentDescription`, `urgency` and `impact`, plus any extra fields in `data`.

`Update` updates a record by `sysId` in a `table` using `data`.

`Delete` removes a record by `sysId` from a `table`.

## Native APIs and permissions

| Task | API | Additional instance access |
|---|---|---|
| `CreateChange` | Change Management REST API | Write access to changes; reference-table reads for human identifiers; approval-table reads when requesting approval |
| `WaitForApproval` | Change API reads and `sysapproval_approver` audit reads | Read access to approvals and approver name/email fields |
| `TransitionChange` | Change API reads, `nextstates` and PATCH | Change write access and allowed model transitions |
| `LoadImportSet` | Synchronous Import Set API | Staging-table and transform-map access; configure coalescing on the server |
| `UpsertCI` | IRE/query and IRE | Registered discovery source, IRE roles and CMDB read access when verifying |

Change operations do not need Table API access to `change_request`. Reference and approval-audit tables have their own ACLs. ServiceNow's API roles and instance rules still apply; test the integration user on a controlled instance.

Native requests have connect/read ceilings of 5/15 seconds, shortened by the remaining task budget, and a 4 MiB response ceiling before JSON parsing. They do not follow redirects or retry transport/server failures. OAuth authentication can retry a rejected HTTP 401 request once with a fresh token. All new tasks support the inherited `timeout` property and stop remaining polling/writes on cancellation. A larger core timeout is accepted; the internal polling budget is the smaller of that value and the task's cap.

## CreateChange

`model` defaults to `NORMAL`; `EMERGENCY` is also supported. These use the JSON-capable `/api/sn_chg_rest/change` endpoint with an explicit type. `STANDARD` requires a published `standardTemplate` sys_id and uses its template endpoint. An optional `changeModel` name/sys_id selects a custom model for normal/emergency requests. Model lookups resolve the API's required name and verify the resulting stored model sys_id.

The documented STANDARD endpoint accepts field overrides in query parameters and has no request-body fields. The complete encoded creation URL is limited to 2048 bytes, including the base URL, path and overrides. Shorten overrides if the limit is exceeded. Query text can appear in proxy and instance access logs; do not put secrets or sensitive free text in STANDARD overrides. Use NORMAL/EMERGENCY for long or sensitive content. STANDARD descriptions and implementation/backout/test plans come from the published template, so those overrides are rejected before creation.

Set `shortDescription`, optional description/plans, assignment group, configuration item and planned dates. Typed fields override `data`. Values are bounded scalars; `data` cannot override identity, state or approval. Human group/CI names are resolved with bounded lookups and ambiguity fails before creation. Supplied fields are read back; ignored or ACL-blocked fields fail rather than silently accepting an incomplete change.

`requestApproval: true` resolves `approvalState` (default `Assess`) through the current model, requests that transition and waits for approval records. This requests approval, it does not approve as the integration user. Default timeout is 30 seconds, maximum 5 minutes. A creation is not transactional or idempotent: setup failures include the created sys_id so the caller can inspect it before retrying.

## WaitForApproval

The task reads the change's global `approval` through the Change API. `requested` waits, `approved` succeeds and `rejected` fails by default. A missing/unknown decision, cancelled change, missing audit or timeout fails. `failOnRejection: false` explicitly returns `REJECTED`.

Default `interval` is 30 seconds (positive, at most 5 minutes). The inherited task `timeout` defaults internally to 3 days and is capped at 7 days. The task reads every bounded approver audit record, then rechecks the decision before returning. `maxApprovers` defaults to 1000 and is capped at 10000; exceeding it fails instead of truncating the audit.

The instance owns its approval rounds. Set `approvedState` to the state value/label required by the downstream operation when its model exposes approval earlier. Audit rows include pending, cancelled and historical decisions. `updatedAt` is `sys_updated_on`, not a guaranteed decision-event time.

`fetchType` supports `FETCH` (default approver list), `FETCH_ONE` (first record), `STORE` (ION URI) and `NONE` (count). Rejection failures include a stored audit URI.

## TransitionChange

Set `sysId` and `to` as an instance state value or label. For closing, set the instance's `closeCode` API value (for example `successful`) and `closeNotes`.

Closed defaults to ordered `Scheduled`, `Implement`, `Review` waypoints before the final target. Set `via` for custom models or `[]` for a direct transition. The task skips already reached waypoints, resolves each next target through fresh `nextstates`, checks model conditions, rereads state/revision immediately before PATCH and verifies the resulting state and closure fields. It does not infer a full future model graph from a current-state response or choose cancellation as a route to Closed.

At most 32 distinct waypoints are accepted. Default timeout is 5 minutes, maximum 1 hour. Refused transitions include available model-condition details. An ineffective HTTP 200 write still fails. Rerunning a completed transition is a no-op; work notes are sent only with the final transition and are not appended again. Journal-event readback is not claimed.

## LoadImportSet

Set `stagingTable` and `from` using a map, list of maps, JSON string or ION file URI through core `Data`. File contents are data and are not rendered as expressions. Strings, finite numbers and booleans become strings, as the synchronous Import Set contract requires. Null/nested values fail before the first write.

The task uses synchronous single-row submissions, including for a bounded file input. `/insertMultiple` is not used because its default asynchronous acknowledgement does not contain completed per-row transform results and its default mapping uses column labels.

Input limits: default `maxRows` 1000, maximum 10000; total 8 MiB; at most 256 fields and 1 MiB per row; at most 65536 characters per field. Input reading is bounded to 30 seconds or the remaining task budget. Default task timeout is 10 minutes, maximum 24 hours.

Each input row can run multiple transform maps. `counts` count transform outcomes, while `submittedRows` counts decoded input responses. `INSERTED`, `UPDATED`, `IGNORED`, `SKIPPED` and `ERROR` are reported. `failOn` defaults to `[ERROR]`; ignored is a valid no-change result. Unknown or malformed outcomes always fail. `failFast` defaults true; false finishes the remaining input before reporting configured failures.

Results default to an ION file (`STORE`) with input index, import set, transform map, target, status and message. `FETCH`, `FETCH_ONE` and `NONE` are also supported. A failed load reports its partial-results URI. An interrupted/failed request may already have been accepted, so inspect the last request and configure server-side coalescing before retrying. The batch is not transactional.

`changeSysId` optionally rereads an approved, non-terminal change immediately before each row write. This catches a decision revoked after an earlier wait; it is not an atomic payload-approval binding.

## UpsertCI

Set `className`, registered `dataSource` and scalar `values`. The task queries IRE for identification, then commits through IRE. It never falls back to direct Table API CMDB writes. An optional stable `sourceNativeKey` is passed in object-source information.

`owned_by` resolves a sys_id, email, user_name or name; use `referenceTables` for additional reference fields. Ambiguous matches fail. IRE's server rules still own identification and reconciliation, and may return no-change or reclassification outcomes.

`verify` defaults true: read the identified CI before the write, then verify requested persisted fields after it. Wrong identities, unknown operations, per-item errors and silently dropped fields fail even on HTTP 200. Read-only Table API calls are used to discover the actual CI class and verify its fields. Set `verify: false` only when those read ACLs are unavailable; the output then says `verified: false`.

`dryRun: true` uses IRE/query without a committed write. Proposed inserts have no committed sys_id. `changeSysId` optionally checks approved/non-terminal status immediately before committing. The check is not atomic with IRE. Default timeout is 1 minute, maximum 10 minutes.

## Example approval, import and close flow

Configure a synchronous transform map for `imp_computer`, including a coalesce key such as `serial_number`. An authorized person must approve through the instance's model. For custom models, adapt the final approved state, waypoint list and closure values.

```yaml
id: servicenow_approved_inventory
namespace: company.operations
tasks:
  - id: change
    type: io.kestra.plugin.servicenow.CreateChange
    domain: "{{ secret('SNOW_DOMAIN') }}"
    username: "{{ secret('SNOW_USERNAME') }}"
    password: "{{ secret('SNOW_PASSWORD') }}"
    shortDescription: Update web-01 inventory
    assignmentGroup: Change Management
    implementationPlan: Import the inventory through the configured transform map
    backoutPlan: Restore the previous inventory values
    testPlan: Confirm the transform result and target CI
    requestApproval: true
  - id: approval
    type: io.kestra.plugin.servicenow.WaitForApproval
    domain: "{{ secret('SNOW_DOMAIN') }}"
    username: "{{ secret('SNOW_USERNAME') }}"
    password: "{{ secret('SNOW_PASSWORD') }}"
    sysId: "{{ outputs.change.sysId }}"
    approvedState: Scheduled
    timeout: P3D
    fetchType: STORE
  - id: inventory
    type: io.kestra.plugin.servicenow.LoadImportSet
    domain: "{{ secret('SNOW_DOMAIN') }}"
    username: "{{ secret('SNOW_USERNAME') }}"
    password: "{{ secret('SNOW_PASSWORD') }}"
    stagingTable: imp_computer
    changeSysId: "{{ outputs.change.sysId }}"
    from:
      - name: web-01
        serial_number: ABC123
        ram: 8192
  - id: close
    type: io.kestra.plugin.servicenow.TransitionChange
    domain: "{{ secret('SNOW_DOMAIN') }}"
    username: "{{ secret('SNOW_USERNAME') }}"
    password: "{{ secret('SNOW_PASSWORD') }}"
    sysId: "{{ outputs.change.sysId }}"
    to: Closed
    closeCode: successful
    closeNotes: Inventory import completed
```

## Verification and limits

With JDK 21, `./gradlew --no-daemon spotlessCheck check shadowJar` runs formatting, compilation, tests, plugin-doc lint and packaging. Functional tests use unconditional WireMock fixtures and include rejected/expired approvals, changing model state, HTTP 201 transform errors, partial loads, IRE errors, failed readbacks and malformed/oversized responses.

Fixture and built-jar runs do not verify a live ServiceNow instance's roles, custom models, transform maps or reconciliation priorities. State/approval rereads are not compare-and-swap. A remote write may commit before a transport failure; do not add blanket write retries. Timestamp and journal limitations are described above.

## API references

- [Change Management API](https://www.servicenow.com/docs/r/zurich/api-reference/rest-apis/change-management-api.html)
- [Import Set API](https://www.servicenow.com/docs/r/zurich/api-reference/rest-apis/c_ImportSetAPI.html)
- [Identification and Reconciliation API](https://www.servicenow.com/docs/r/zurich/api-reference/rest-apis/c_IdentifyReconcileAPI.html)
