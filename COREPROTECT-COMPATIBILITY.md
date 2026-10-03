# CoreProtect integration on Canvas 26.2

## 26.10.0 admission and completion contract

Use FAWE 26.10.0 with the matching CoreProtect 26.10.0 fork. Replacing only FAWE does
not give older CoreProtect versions downstream recovery admission control.

`IBatchProcessor.prepareChunk` runs on a worker before the chunk GET call lock and
`processSet`. It may wait for bounded downstream capacity. It must return a non-null
scope, release unused reservations on close, and transfer consumed reservations to
the downstream consumer. The scope closes after scheduling, not after world-write
completion. Deferred logging callbacks must retain their own reservation references.
Preparation failure prevents that chunk from reaching `processSet` or native mutation.

The chunk executor has a bounded waiting queue. Streaming edit queues limit prepared
chunks independently of executor utilization. `queue.admission-timeout-ms` defaults
to 30000; tick threads cannot wait or run rejected work inline. Memory pressure is
checked while allocating/submitting chunks, including undo operations that bypass
the initial edit-session memory check. Accepted writes are drained before completion
or failure is returned, including their history callbacks.

Each edit owns its queue. Creating another edit on the same worker cannot reset the
first edit's prepared chunks or processors, and completed edit state is not retained
by a permanent per-thread queue cache. World snapshot caches remain shared.

Cancellation stops unstarted chunks without replacing the history processor for
accepted writes. Chunk and history failures propagate to the operation; a partial
edit is not reported as successful. Failed history snapshots remain attached to the
change set, and history I/O errors are not suppressed. A storage failure is not a
promise that those snapshots are crash-safe: preserve the process and storage for
recovery rather than repeatedly retrying the edit.

These limits do not cap the entire JVM. Clipboard contents, retained undo sessions,
world/chunk caches, and other plugins still consume memory. Explicitly disabled
streaming queues and third-party processors have separate retention behavior. Audit
admission weights are conservative accounting units, not measured JVM object sizes.
Edits that cannot fit downstream capacity fail explicitly; logging is never bypassed.

### Incident evidence

The October 2 incident ran FAWE `2.15.5-folia.8+3953b6b`, CoreProtect `24.0-folia.10`,
and Canvas `26.2-941-4a0ed14`. Heap warnings appeared hours before the paste/undo
sequence. The available production logs establish saturation and heap exhaustion,
not the dominant heap owner. A controlled CoreProtect reproduction demonstrated
retained rejected captures, while FAWE source/regression tests demonstrated unbounded
chunk submission and failures omitted from completion reporting.

Build checks and native probe instructions are below. Native verification uses an
isolated Canvas server, never a live world; see `verification/incident/README.adoc`.

### Verified release checks

- 239 core/Bukkit tests passed; the artifact contains Java 25 bytecode and only the 26.2 adapter.
- Canvas 941 with four region threads and a 1536 MiB heap completed two full paste/undo
  cycles of the incident schematic (76 x 159 x 127 cells). Every block state was checked
  after each paste and undo; CoreProtect committed exactly 2,534,392 matching block records.
- Cancellation preserved 8,192 completed blocks, 8,192 history entries and 8,192 audit
  records. Undo removed every completed block and brought the audit count to 16,384.
- Paired 1 GiB tests covered concurrent edits (262,144 audit records), a hard storage
  timeout (32,768 completed blocks matching audit records), and restart without duplicates.
  The final concurrent test recorded 969 unrelated-region ticks with a maximum gap of 54 ms.
- CoreProtect's separate tests covered 22 metadata scenarios, legacy-journal replay and
  saturated shutdown/replay of 10,000 records. Its head capture uses supplied NBT rather
  than a delayed live read, and its admission scan uses one old-NBT snapshot per chunk.

Native tests used Java 25.0.1 on Windows, not the production Java 25.0.4.1/Linux runtime.
Temporary CoreProtect capture-backlog warnings remained visible while its bounded writer
drained; final shutdown reported no unsafe captures, no tracked durable records, and no
queued recovery bytes. These checks do not establish the original production heap owner
or guarantee the behavior of the entire production plugin stack.

## Earlier compatibility fixes

`2.15.5-folia.6` invalidates a cached chunk view when the server has unloaded or replaced its chunk. Reusing the old chunk object could return an empty chest inventory even though a direct read of the current chunk contained items. The 26.2 adapter also materializes block-entity NBT on the owning region before returning the snapshot to a worker.

Use this build with CoreProtect `24.0-folia.2`, which records container, sign, and spawner metadata supplied through FAWE and preserves explicit waterlogged block data.

The native regression fixture is maintained in [CoreProtect's FAWE integration probe](https://github.com/ANO-Development/CoreProtect/blob/folia-26.2/src/test/java/net/coreprotect/integration/FaweIntegrationProbe.java). It verifies live source inventories against queued NBT snapshots and repeats chest replacement, no-op, rollback, and restore cycles five times on a disposable Canvas server. It also exercises block and clipboard edits, history, six container types, formatted signs, and spawners on SQLite and DuckDB. See its [run instructions](https://github.com/ANO-Development/CoreProtect/blob/folia-26.2/docs/folia-26.2.md#fawe-integration).

Build and run the relevant Gradle checks with JDK 25:

```powershell
.\gradlew.bat :worldedit-core:test :worldedit-bukkit:test :worldedit-bukkit:adapters:adapter-26.2:test :worldedit-bukkit:shadowJar
```

The current Canvas artifact is `worldedit-bukkit/build/libs/FastAsyncWorldEdit-Paper-26.10.0.jar`.
