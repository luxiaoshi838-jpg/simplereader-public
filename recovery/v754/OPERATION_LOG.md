# SimpleReader v754 operation log

## 2026-08-31 — recovery/build session

### Permanent execution rule
- Any single step with no new observable progress for 5 minutes must be checked immediately.
- Observable progress means at least one of: a new Git commit, new CI step/status, new log output, new artifact/file, or a completed verification result.
- If the first check shows no progress, identify the blocking step instead of waiting blindly.
- If two consecutive checks still show no progress, treat the step as stalled and switch to a different execution path.
- Long operations must be reported by state, not assumed to be running.

### Stall incident
- `source-v754` last effective upload before the check: commit `ad56966a1f969a3929cf1ac8009031d21808b852`, message `v754: upload recovery delta part 007`.
- Commit time: 2026-08-31 02:24:07 UTC (10:24:07 UTC+8).
- GitHub Actions run `33350556355` started 02:24:09 UTC and completed successfully at 02:29:11 UTC.
- The Actions job was not stalled: unit tests, debug build, and release validation build all completed successfully.
- The actual stall was the assistant-side continuation of the recovery-delta upload: no further source-recovery commits were produced for hours after part 007.
- Corrective action: stop treating the upload as still running; resume from the verified checkpoint and use shorter, auditable steps.

### Failed recovery attempt after stall check
- Recovery/build workflow run `33361740137` failed quickly and was inspected immediately rather than waited on.
- Failure point: reassembly of the old `recovery/v754/delta/part-*.b64` set.
- Reassembled truncated payload SHA-256: `858d0ef2a0c19e8794ada4b278c1c3a1b7bc7667a130b158a1f11ebde80e7c0b`.
- `unzip -t` reported a missing end-of-central-directory signature, proving the old eight-part payload was incomplete.
- Therefore the old eight-part recovery payload is deprecated and must not be used for release construction.

### Exact recovery checkpoint
- Exact public base source was exported from branch `source-v748`, commit `d13fbe3a22397bcb4e6c5773441d241494a57584`, through Actions run `33362036026`; export job completed successfully.
- The source-v748 artifact was downloaded locally and compared file-by-file against the verified v754 source tree.
- Verified v754 source ZIP SHA-256: `ce836ce5edb23d72cd07e6aad3aefe8bb99f3fdce98d6d1d574d5e19fe713a64`.
- Exact source-v748 -> v754 recovery delta ZIP SHA-256: `0798585777a088e19cb0714bec24324d6176073d63b110003e667260e72e8b28`.
- Exact managed-source difference: 43 changed files, 18 added files, 63 deleted files; 111 managed files are byte-identical and require no transfer.
- The exact delta ZIP passes ZIP integrity validation and contains `DELTA_MANIFEST.json` plus `DELETE_PATHS.txt`.
- Any transfer format used from this point must declare an exact part count and must reproduce the delta SHA-256 before files are applied.
- Text transfer tests found 20 KB chunks can be truncated by the connector response path; 16 KB and below were observed intact. Unsafe chunk sizes must not be used.

### Second stall after shortest-pipeline decision
- At 2026-08-31 17:01 UTC+8 the user reported another stall.
- Inspection showed `source-v754` was still at commit `7346e3dab6c59748e1a9aff4563031550243adb9`, created 2026-08-31 15:45:54 UTC+8.
- The associated Android validation run had already completed successfully at 2026-08-31 15:50:51 UTC+8.
- No later source-sync commit or build run existed by 17:01 UTC+8.
- Therefore this was not a GitHub/CI stall. The assistant stopped after planning `Git blob -> tree -> commit -> ref` synchronization and failed to perform the actual Git writes.
- Corrective rule: after a synchronization strategy is chosen, no further alternative-route exploration is allowed until at least one concrete Git object/tree/commit/ref write has been completed and verified.

### Release discipline
- v754 is based on the user-provided original v753 source ZIP whose SHA-256 matched the historical v753 source record, plus the v754 catalog/UI changes.
- Never upload the private keystore or password to the public repository.
- Public GitHub may build an unsigned APK; final signing must use the existing SimpleReader Public V1 key outside the public repository.
- Do not call a build/recovery complete until the required gates, build result, GitHub source/log synchronization, and final artifact checks are all present.
