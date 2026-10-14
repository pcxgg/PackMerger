# Testing PackMerger

## Automated tests

Unit tests live under each module's `src/test/java` (e.g. `packmerger-plugin/`,
`packmerger-bedrock/`, `packmerger-common/`) and run with `./gradlew test` (also run
by `./gradlew build` and by CI on every push/PR — see `.github/workflows/ci.yml`).

Current coverage is strong for the **pure-logic layer**: every merge strategy,
the JSON merger, orphan detection, the pack-format registry, profile/S3 config
records, priority mutation, inspect rendering, update-version comparison, and the
remote-pack static helpers.

### Known gap — the I/O / orchestration layer

`PackMergeEngine`, `PackValidator`, `RemotePackManager.fetchAll`, `PackDistributor`,
and the three upload providers all take a `PackMergerBootstrap` (and through it the
Bukkit API) in their constructors, so they can't be unit-tested as-is. Closing
this gap is a follow-up that needs one of:

1. **Add [MockBukkit](https://github.com/MockBukkit/MockBukkit)** as a
   `testImplementation` dependency and stand up a mock server in tests. Lowest
   churn; lets the existing plugin-coupled classes be exercised directly.
2. **Decouple the logic from the plugin** — extract the merge/validate/fetch/upload
   logic behind plain interfaces that take only their inputs (files, config
   records, an HTTP client), leaving the `PackMergerBootstrap`-aware classes as
   thin adapters. More work, but the logic becomes testable without any Bukkit
   harness and the seams help future maintenance.

Highest-value targets once a harness exists:

- **merge → validate → rollback**: a validation failure must keep the previous
  `<output>.zip` live and fire `PackValidationFailedEvent` with `rolledBack=true`.
- **`RemotePackManager` against a stub `HttpServer`**: 200 / 304 ETag-reuse,
  HTTPS-only rejection (unless `allow-http`), and fetch-fail-falls-back-to-cache.
- **upload providers**: a `SelfHostProvider` loopback round-trip (serve + the 429
  rate-limit path) and `PolymathUploadProvider` error/timeout handling.

## Manual smoke test (per release)

Run against a real server before tagging a release. The plugin advertises
`folia-supported: true`, so smoke-test on **both Paper and Folia**.

### Paper

1. Drop the shaded jar in `plugins/`, start a Paper **26.3** server (Java 25; the jar is compiled against the 26.1.2 API and must also be checked on a 26.1.2 server).
2. Confirm enable: console shows `PackMerger enabled!` and (at `log-level: debug`)
   the pack-format registry line reporting the server's Minecraft version.
3. `/pm status` — reports the current merged pack + URL.
4. `/pm validate` — the pack-format drift check is **active** (not `UNKNOWN`); on a
   26.1.2 server a current pack reports a match, confirming the registry covers
   the running version.
5. Drop a pack into `packs/`, wait for hot-reload (debounce), confirm a re-merge.
6. Join with a client — the resource pack is sent and applies.

### Folia

Repeat the above on a Folia build of the same version. Pay attention to:

- **Join → pack send**: handled on the player's entity scheduler
  (`PlayerJoinListener`), so it must work without main-thread scheduling errors.
- **Quit mid-send**: disconnect right after joining; the pending send task must be
  cancelled cleanly (no console error).
- **`/pm` commands and the 5-minute cache save**: run on the async scheduler;
  confirm no `BukkitScheduler`-on-Folia exceptions appear.

### Bedrock / Geyser (when `bedrock.enabled: true`)

The converter's deterministic parts are unit-tested (`BedrockConverterTest`), but the
end-to-end result can only be confirmed against a live Geyser server — do this before
relying on it:

1. Install Geyser (+ Floodgate) and merge a pack containing CMD-based custom items.
2. Confirm `output/bedrock/packmerger-<server>.mcpack` and `…geyser.json` are written,
   and (with auto-deploy) copied into Geyser's `packs/` and `custom_mappings/`.
3. Reload/restart Geyser, join with a **Bedrock** client, and verify the custom item
   icons render. Enable `bedrock.debug` to see which items/models were skipped
   (e.g. 3D models, which the items-definition subset does not convert).

### Velocity proxy (network-wide distribution)

The `:packmerger-common` codec is unit-tested (`PackMessagingTest`); the end-to-end
relay needs a real proxy + backend:

1. Build both jars (`./gradlew build`): `packmerger-plugin/build/libs/packmerger-plugin-<v>.jar`
   for each backend, `packmerger-velocity/build/libs/packmerger-velocity-<v>.jar` for the proxy.
2. Set `url=` in the proxy's `plugins/packmerger/config.properties`; join via the proxy
   and confirm the pack is offered.
3. Set `distribution.proxy-notify: true` on a backend, trigger a merge with a player
   online, and confirm the proxy logs "pack updated from backend" and offers the new
   URL to the next join.
