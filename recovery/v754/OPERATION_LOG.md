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

### Release discipline
- v754 is based on the user-provided original v753 source ZIP whose SHA-256 matched the historical v753 source record, plus the v754 catalog/UI changes.
- Never upload the private keystore or password to the public repository.
- Public GitHub may build an unsigned APK; final signing must use the existing SimpleReader Public V1 key outside the public repository.
- Do not call a build/recovery complete until the required gates, build result, GitHub source/log synchronization, and final artifact checks are all present.
