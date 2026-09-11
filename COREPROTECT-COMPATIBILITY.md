# CoreProtect integration on Canvas 26.2

`2.15.5-folia.6` invalidates a cached chunk view when the server has unloaded or replaced its chunk. Reusing the old chunk object could return an empty chest inventory even though a direct read of the current chunk contained items. The 26.2 adapter also materializes block-entity NBT on the owning region before returning the snapshot to a worker.

Use this build with CoreProtect `24.0-folia.2`, which records container, sign, and spawner metadata supplied through FAWE and preserves explicit waterlogged block data.

The native regression fixture is maintained in [CoreProtect's FAWE integration probe](https://github.com/ANO-Development/CoreProtect/blob/folia-26.2/src/test/java/net/coreprotect/integration/FaweIntegrationProbe.java). It verifies live source inventories against queued NBT snapshots and repeats chest replacement, no-op, rollback, and restore cycles five times on a disposable Canvas server. It also exercises block and clipboard edits, history, six container types, formatted signs, and spawners on SQLite and DuckDB. See its [run instructions](https://github.com/ANO-Development/CoreProtect/blob/folia-26.2/docs/folia-26.2.md#fawe-integration).

Build and run the relevant Gradle checks with JDK 25:

```powershell
.\gradlew.bat :worldedit-core:test :worldedit-bukkit:test :worldedit-bukkit:adapters:adapter-26.2:test :worldedit-bukkit:shadowJar
```

The Canvas artifact is `worldedit-bukkit/build/libs/FastAsyncWorldEdit-Paper-2.15.5-folia.6.jar`.
