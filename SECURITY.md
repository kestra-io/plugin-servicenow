# Native ServiceNow task boundaries

These tasks operate with the configured integration user's permissions. ServiceNow owns change models, approval decisions, transform maps, source priorities and reconciliation. Use a least-privilege integration user and a controlled instance to verify those policies.

| Threat | Defense | Verification |
|---|---|---|
| Credentials exposed by task strings or native error details | Secret/string exclusions and captured credential redaction in native errors; no native payload logging | `ServiceNowNativeTest`, `LoadImportSetTest` |
| Unbounded/malformed API payload | Core streamed response with a 4 MiB ceiling before JSON parsing; object/field/outcome checks; bounded connect/read time | `ServiceNowNativeTest.shouldEnforceTheResponseCeilingBeforeParsing`, malformed-response tests |
| Query injection or ambiguous human identifier | Restricted lookup syntax, safe API names, encoded parameters, at most two matches and identity checks | Native reference and `CreateChangeTest` tests |
| Wrong record returned for a request | Returned sys_id must match the requested identity before use | `ServiceNowNativeTest.shouldRejectWrongRecordIdentityBeforeAuditingApproval` |
| HTTP success falsely treated as business success | Per-transform and per-IRE-item status/error checks, model conditions and persisted-field readback | `LoadImportSetTest`, `UpsertCITest`, `TransitionChangeTest` |
| Decision/state changed after planning | Fresh approval before optional gated writes; state/revision reread before PATCH; decision reread after audit | Revocation/revision tests in the task suites |
| Partial/interrupted load replayed | Stored partial result URI, no native automatic write retry, server-side coalescing required | Failure and kill tests in `LoadImportSetTest` |
| Cancelled worker keeps polling or writing | Per-run lifecycle cancellation signals and bounded HTTP budgets; non-blocking kill/stop | Early/in-flight kill tests and `WaitForApprovalTest` |
| Input consumes unlimited resources | Row/field/text/byte limits, bounded Data read, bounded transform/audit/waypoint counts | Input/response boundary tests and state-sign invariants |

## Known limits

- Read/check/write is not an atomic conditional operation. The API does not provide a documented compare-and-swap used here; the server must enforce its model and ACLs at commit time.
- `changeSysId` checks current approval and terminal state, not a hash of the exact inventory payload approved by a person.
- A timed-out write may already be committed. Inspect the remote state before retrying. Import coalescing and IRE identification are instance configuration, not a client-side duplicate guarantee.
- Approval `updatedAt` is the audit record's last update, not a proven decision-event timestamp. Native journal-event readback is not claimed.
- Closed waypoints have out-of-box defaults; custom models must supply their own `via` values. The client does not invent unseen model edges.
- Tests use WireMock and an isolated built-jar fixture. Live ServiceNow model, ACL and transform behavior requires separate instance verification.

Run the release gate with `./verify.sh`. Report a reproducible security issue to the repository maintainers through their published reporting channel; omit credentials and private instance data.
