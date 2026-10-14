# Changelog

All notable changes to PackMerger are documented here. The format is loosely
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

- Hosting route wording now distinguishes a failed reachability check from a non-routable auto host; the file watcher warns when it cannot watch every folder (inotify limit); `/pm reload` republish works on a stable copy of the output zip.

Delivery reliability: every player gets the pack, every time, and the pack that is
advertised is always the pack that is served. Self-hosting works out of the box, and
tells you exactly what to fix when it can't.
Plugin packs are discovered from many more plugins and re-merged when a plugin
rebuilds its pack, and colliding item definitions, models and overlays are merged
the way the client actually reads them.

### Added
- **Self-heal resend**: `FAILED_DOWNLOAD` or `DISCARDED` triggers a resend after `distribution.retry.delay-ticks` (default 40), at most `distribution.retry.max-attempts` (default 3) times per session. `INVALID_URL` and `FAILED_RELOAD` are not retried; a specific diagnosis is logged instead.
- **`distribution.enforce-required`** (default off): with `required: true`, kick players who decline the pack, or whose download still fails after the retries, with a configurable MiniMessage `kick-message`.
- **`distribution.skip-bedrock-players`** (default `true`): Floodgate (Bedrock) players are not sent the Java pack. Detected through the Floodgate API when Floodgate is installed.
- **Reachability verification** (`upload.self-host.verify`, default `true`): after every upload the first KiB of the pack is fetched from `127.0.0.1:<port>` and from the advertised URL and compared byte for byte (the latter catches closed ports, missing NAT hairpinning and misrouted proxies). It runs off the main thread, and the new pack is only served at `/pack` once it is published (the check uses a one-off `?pm-verify=` token), so the advertised SHA-1 always matches what `/pack` serves. Failures are logged, sent to online admins and shown in `/pm status`.
- **`upload.fallback`** (default `[]`): providers to publish through, in order, when `upload.provider` fails to upload or (self-host) fails verification or has a non-routable auto host, e.g. `["s3"]`. If nothing better works, a self-host URL that failed its check is still used rather than sending no pack.
- **`upload.self-host.public-host`**: host name or IP for the auto-built URL.
- **`upload.self-host.detect-public-ip`** (default `false`) and `public-ip-services`: opt-in lookup of the server's public IP. This contacts a third party (by default Amazon checkip, then ipify), which learns the server's IP; it runs once per start/reload and only when `public-url` and `public-host` are empty.
- **`/pm status`** shows the hosting route (and why a fallback was used), the self-host port and whether it is listening, the resolved host and where it came from (with the exact setting to change when it is not routable), and the last verification result with its reason and time.
- Admins (`packmerger.admin`) get a notice a few seconds after joining when players probably can't download the pack.
- Self-host warns when its port is out of range, equals the Minecraft port, or collides with `rcon.port`/`query.port` (when enabled).
- `messages_en.yml` keys missing from an older copy on disk now fall back to the bundled text instead of showing the raw key.

- **13 more plugin-pack integrations**: Nova, BetterHUD, ValhallaMMO, vane-core,
  BackpackPlus, BetterStructures, CannonRTP, EternalTD, InfiniteVehicles,
  MegaBlockSurvivors and ResurrectionChest from disk, plus MMOInventory and
  RealisticSurvival, whose packs are downloaded from the author's published link
  only while the plugin is installed (opt out with
  `plugin-packs.sources.<alias>.enabled: false`).
- **Cluster sources**: a folder holding several packs is unioned into one staged
  pack (lang/atlas/sounds/model JSON merged, first pack wins other clashes).
  Folders of packs are detected automatically; `cluster: true` forces it.
- **Operator-defined adapters**: any alias under `plugin-packs.sources` that is not
  built in becomes an adapter (`plugin`, `path` (string or list), `cluster`,
  `enabled`), so new plugins need no release. Built-ins accept the same keys, and
  `url` for the two download-only plugins.
- **Plugin pack watcher** (`plugin-packs.watch.enabled`, `interval-seconds`,
  default on / 5 s): polls the resolved source paths and re-merges when a plugin
  rebuilds its pack, so `/oraxen reload`-style rebuilds no longer need a `/pm`
  command.
- **Stability check** (`plugin-packs.stability.polls` / `interval-millis`, default
  3 / 1000): a changed source is copied only once its size and mtime hold steady,
  so half-written zips are never staged; a still-changing source is retried.
- **`/pm merge`**: re-stages plugin packs, merges and uploads without reloading the
  config. It was documented before but did not exist.
- `/pm status` lists the plugin packs merged on the last pass.
- ItemsAdder zip protection and `compress-json-files` are detected and reported in
  the admin advisory.

- **`merge.model-policy`** (`whole-file` | `deep`) controls how colliding models
  and blockstates are combined. See "Changed" for the new default.
- **Merge notes in provenance**: every collision warning and automatic rewrite a
  merge strategy makes is stored with the file in the provenance sidecar and shown
  by `/pm inspect collisions` and `/pm inspect export`. The console prints one
  summary line per merge.
- **Overlay format repair**: when two packs' overlay lists are combined and any
  entry reaches format 64 or below, entries that only declare
  `min_format`/`max_format` gain the matching `formats` range, which 1.21.9+
  clients require for the list to load. The merged `pack.mcmeta` also declares the
  union of both packs' format ranges in a consistent shape (`min_format`/
  `max_format`, plus `supported_formats` and an in-range `pack_format` when the
  range reaches format 64 or below).

### Fixed
- **Returning players got no pack.** The persistent player cache skipped the send on rejoin when the hash matched, but the client drops server packs on disconnect, so those players played without the pack (and `required` was never applied to them). The pack is now sent on every join; the client's own SHA-1 cache still avoids re-downloading an unchanged pack.
- **Every merge was a "new pack".** Zip entries carried the current time, so identical content produced a different SHA-1 each merge (notify/resend spam, client re-downloads, S3 object churn). Merged and staged plugin zips are now deterministic (sorted entries, fixed timestamps).
- **Hash/URL mismatch window.** The new SHA-1 was advertised before the upload finished, and a failed upload left the new hash paired with the old URL. The `(URL, SHA-1, pack UUID)` record is now published as one unit only after the upload succeeds; a failed upload keeps the previous pack live (and fires `PackUploadFailedEvent`). Self-host serves a snapshot (`output/.self-host/<sha1>.zip`), so a running merge never changes the bytes behind an advertised hash, and `upload()` fails instead of advertising a URL when the HTTP server could not bind.
- **No pack after a restart or `/pm reload` until a merge succeeded.** The existing merged pack is now re-published immediately on startup and after `/pm reload`, so validation rollback really keeps the previous pack live, and self-host no longer answers 503 after a reload. The self-host HTTP server keeps running through `/pm reload` when its port is unchanged.
- **Players who joined before the first merge never got the pack** (with the default `on-new-pack: notify`). They are now sent it as soon as the first pack is published.
- **Pack stacking and stripping other packs.** The pack UUID was derived from the hash, so add-mode stacked a new copy next to the stale one, and replace mode called `clearResourcePacks()`, removing packs sent by the proxy or other plugins. PackMerger now uses one stable UUID per server (`nameUUIDFromBytes("packmerger:" + server-name)`) and only ever removes its own pack.
- **Status handling ignored the pack id.** A status report for another plugin's pack was treated as PackMerger's. Reports are now filtered by PackMerger's pack UUID.
- **Self-host advertised `http://localhost:<port>/pack` on most servers**, silently. The URL host is now resolved from `public-url`, then the new `public-host`, then an opt-in public IP lookup, then `server-ip`, then the bind address, preferring the first publicly routable one. A loopback/private/link-local/CGNAT/`0.0.0.0`/ULA result is still used (LAN players can download) but logged as a warning on every merge, shown to admins on join and explained in `/pm status`.
- **`public-url` could not point at an arbitrary path**: `/pack` was appended unless the URL already ended in `/pack`, so `https://cdn.example.com/rp.zip` became `.../rp.zip/pack`. `public-url` is now a complete URL; `/pack` is only appended when the URL has no path at all (`http://host:port` or `http://host:port/`). URLs ending in `/pack` behave as before.
- **No check that the pack could actually be downloaded.** See "Reachability verification" below.
- **Self-host HTTP handler**: `/pack` was a prefix match (`/packfoo` served the pack), `HEAD` returned 405, and there was no `ETag`, `If-None-Match` or `Range` support. Now: exact path matching, `GET`/`HEAD`, strong SHA-1 `ETag` with `304`, single-range `206`/`416`, `Cache-Control: no-cache`; the rate limiter is unchanged (HEAD and 304 don't take a download slot). A download streams from the file it opened, so publishing a new pack mid-download can't break it, and old snapshots that are still open (Windows) no longer make the upload fail.
- The self-host request threads are shut down with the HTTP server (they leaked on every provider switch).
- Config/README drift: `debug: false` (the key is `log-level`), the provider list now includes `s3`, and the rollback comment no longer says "once enabled in 1.1.0". (`/pm merge`, which the docs already mentioned, now exists; see Added.)

- **1.21.4+ item definitions are merged correctly.** Two packs defining the same
  `items/*.json` as `range_dispatch` on the same property now keep both packs'
  `entries` (unioned by threshold, sorted, higher priority wins a shared threshold
  with a warning naming both packs); `select` nodes on the same property have
  their `cases` unioned by `when`; matching `fallback` nodes are merged too.
  Before, the lower-priority pack's entries were silently dropped, and mismatched
  node types produced an invalid hybrid with both `cases` and `entries`.
  Non-combinable files now go to the higher-priority pack whole, with a warning.
- **Overlay directories are merged format-aware.** `<overlay>/assets/...` files used
  to bypass every merge strategy (plain overwrite); they now get the same lang,
  model, item-definition, atlas, font, sounds and blockstate handling as `assets/`.
- **Legacy `overrides` are sorted by `custom_model_data`** after merging (entries
  without one first), so CMD thresholds from different packs resolve correctly.
- **Hot reload sees nested edits.** The `packs/` watcher now watches every folder
  inside it (dot-folders excluded), so editing a file inside an unzipped pack
  folder triggers a merge.
- **EliteMobs and FreeMinecraftModels** are read from their current locations:
  `EliteMobs/resource_pack` (a folder of packs) and
  `FreeMinecraftModels/output/FreeMinecraftModels.zip` plus the
  `FreeMinecraftModels/resource_pack` folder of packs. The old paths stay as
  fallbacks.
- **Broken fallbacks removed**: `Nexo/pack/assets`, `ItemsAdder/contents` and
  `Oraxen/pack` produced packs with nothing usable at the root. Every staged
  source is now validated (`pack.mcmeta` or `assets/` at the root; a zip wrapped in
  one folder is unwrapped); a source that fails is skipped, logged, and listed in
  the admin advisory.
- **Stale staged packs no longer merge**: disabling an adapter, removing the
  plugin, or deleting its output now deletes `packs/.plugin-packs/<alias>.zip`;
  turning `plugin-packs.enabled` off clears the folder. Cached downloads in
  `packs/.remote-cache/` whose alias was removed from `remote-packs` are deleted
  too.

### Changed
- **`upload.self-host.port` defaults to `-1` (Minecraft port + 1)** instead of `8080`. Configs that set a port explicitly (the generated config.yml always did, with `8080`) keep that port; only a config without the key moves to Minecraft port + 1. On hosting panels, allocate that port (or set an allocated one).
- **`public-url` is used as a complete URL** (see Fixed). If you relied on `/pack` being appended to a URL with another path (e.g. `https://example.com/mc` meaning `https://example.com/mc/pack`), write the full URL.
- **`distribution.cache` was removed.** Existing configs keep working; the section is ignored and a one-line notice suggests deleting it. `plugins/PackMerger/cache/player-cache.json` is no longer read or written and can be deleted.
- **`use-add-resource-pack: false` no longer clears every server pack.** It removes PackMerger's own previous pack and sends the new one; packs from a proxy or other plugins stay applied. `true` sends without the explicit removal (the stable UUID makes the client replace PackMerger's pack in place).
- **`distribution.required` only sets the client flag**, as before, but the config no longer claims that players are kicked; enable `enforce-required` for that.
- **Bedrock players no longer receive the Java pack** by default (`skip-bedrock-players: true`).
- `PackSentToPlayerEvent` is fired on the player's region thread; `bypassedCache()` now means "forced send" (`/pm apply`, `on-new-pack: resend`, self-heal resend, or the first publish reaching an early joiner).
- `getCurrentPackUrl()` / `getCurrentPackSha1Hex()` describe the published pack and always change together.
- A re-merge with identical content logs `Merged pack SHA1: <sha1> (unchanged)` and does not trigger `on-new-pack`.

- **Default model/blockstate merge is now whole-file** (`merge.model-policy:
  whole-file`). When two packs ship the same model or blockstate, the
  higher-priority file wins whole; only model `overrides` and blockstate
  `multipart` (when both files use it) are still combined. The old key-by-key deep
  merge could build broken hybrids (an `item/generated` model inheriting another
  pack's `elements`, a blockstate with both `variants` and `multipart`). Configs
  without the key get the new default; add `merge.model-policy: deep` to keep the
  previous output.
- `config-version` is now 4 (was 2). New keys: `distribution.enforce-required`,
  `distribution.retry`, `distribution.skip-bedrock-players`,
  `upload.self-host.public-host`, `detect-public-ip`, `public-ip-services`,
  `verify`, `upload.fallback`, `plugin-packs.watch`, `plugin-packs.stability`,
  the extra `plugin-packs.sources` keys and `merge.model-policy`. All default
  safely when missing; `distribution.cache` was removed.
- The plugin-pack watcher is **on by default**. It only reads file sizes and
  timestamps; set `plugin-packs.watch.enabled: false` to get the old behaviour
  (re-stage only on merge).

## [1.2.0] — 2026-06-18

Brings PackMerger onto the 2026 Minecraft/Paper line, adds automatic discovery of
packs generated by other plugins, and closes the biggest tooling gaps.

### Changed

- **Built against Paper 26.1.2 (Minecraft's new year-based versioning) on Java 25.**
  Mojang switched from the `1.21.x` scheme to `<year>.<drop>.<patch>` after 1.21.11;
  the build now targets `io.papermc.paper:paper-api:26.1.2.build.+`, the Gradle
  toolchain is Java 25, and `plugin.yml` `api-version` is `26.1`.
- **Pack-format registry brought current.** Added the late 1.21 line (1.21.7–1.21.8
  → 64, 1.21.9–1.21.10 → 69, 1.21.11 → 75) and the new 26.x scheme (26.1–26.1.2 →
  84, 26.2 → 88). The registry had stopped at 1.21.6, so the `pack_format` drift
  check was silently returning `UNKNOWN` (a no-op) on every server newer than that.
  Startup now logs the registry coverage vs. the running server version at debug.
- **Pack-format guardrail understands 26.1's `min_format`/`max_format`.** Since
  1.21.9 the `pack.mcmeta` format moved from `pack_format` / `supported_formats` to
  `min_format` / `max_format` (plain ints, or `[major, minor]`). The validator now
  reads both schemas, so the drift check actually runs on 26.1 packs — previously it
  saw no `pack_format` and silently skipped. A merged pack still declaring a stale
  format for an older version is now surfaced instead of shipping silently.

### Added

- **Automatic plugin-pack discovery.** PackMerger now detects installed
  pack-generating plugins — Oraxen, Nexo, ItemsAdder, ModelEngine, EliteMobs,
  FreeMinecraftModels — stages each one's current generated pack into
  `packs/.plugin-packs/<alias>.zip` before every merge, and **deep-merges** them
  through the format-aware strategies (not just whole-file overwrite). Reference an
  alias in `priority:` to control precedence; override a plugin's source path or
  disable it under `plugin-packs.sources.<alias>`. Re-staged on every merge, so
  `<plugin> reload` + `/pm merge` always picks up fresh output. Config:
  `plugin-packs` (`enabled`, `merge-delay-seconds`, `sources`).
- **Missing-integration advisory.** When a pack-generating plugin is installed but
  not being merged (disabled, or no generated pack found), admins are warned on the
  console at startup and on join.
- **Auto self-host port.** `upload.self-host.port: -1` derives the HTTP port from the
  Minecraft server port + 1, so operators don't have to pick a free port manually.
- **Bedrock / Geyser conversion (opt-in, experimental).** New `:packmerger-bedrock` module
  converts the merged Java pack's custom items into a Bedrock pack + Geyser
  custom-item mappings so Bedrock/Floodgate players see the same custom items.
  Targets the 1.21.4+ item-definition subset (`custom_model_data` → model → `layer0`
  texture, i.e. 2D item icons; 3D geometry is not yet converted). Writes a `.mcpack`
  + `<name>.geyser.json` to `output/bedrock/` and can auto-deploy them into Geyser's
  `packs/` and `custom_mappings/` folders. Config: `bedrock` (`enabled` — **off by
  default**, `auto-deploy-to-geyser`, `geyser-folder`, `debug`); new
  `PackBedrockConvertedEvent`. Verify against a live Geyser/Bedrock client before use.
- **Velocity proxy module (network-wide distribution).** New `packmerger-velocity`
  plugin offers the merged pack to every player from the proxy, so distribution is
  network-wide instead of per-backend. The proxy reuses the backend's existing
  hosting — give it the pack URL in its `config.properties`, and (optionally) set
  `distribution.proxy-notify: true` on backends to push live URL/hash updates over
  the `packmerger:pack` plugin channel. Shares a new `:packmerger-common` module (the
  `PackInfo` + message codec) with the backend.
- **CI workflow** (`.github/workflows/ci.yml`) running `./gradlew build` (full test
  suite + shaded jar) on every push and PR. Until now nothing ran the tests in CI.
- **Dependabot** (`.github/dependabot.yml`) for the Gradle and GitHub-Actions
  ecosystems, keeping dependencies and Action pins current via CI-gated PRs.
- **Gradle version catalog** (`gradle/libs.versions.toml`) as the single source of
  dependency versions; de-duplicates the MinIO coordinate that was declared twice.
- **`docs/TESTING.md`** documenting the per-release Paper + Folia smoke test and the
  integration-test roadmap for the (currently Bukkit-coupled) I/O layer.
- **`config-version` field** in `config.yml`. PackMerger logs a one-line notice when
  a config predates the current schema, so silently-defaulted new keys are visible.
- **bStats metrics** (plugin id `32086`), shaded + relocated. Anonymous usage stats
  only; opt out globally in `plugins/bStats/config.yml`. Init is guarded so a
  scheduler incompatibility never blocks enable.

### Build / dependencies

- **Monorepo.** Restructured into a Gradle multi-module build under a uniform
  `packmerger-*` naming scheme: `:packmerger-plugin` (the backend jar,
  `packmerger-plugin-<version>.jar`), `:packmerger-velocity` (the proxy jar,
  `packmerger-velocity-<version>.jar`), and the shaded-in libraries
  `:packmerger-bedrock` and `:packmerger-common`. Dependency versions live in the
  shared root `gradle/libs.versions.toml`.
- Gradle wrapper `8.12` → `9.6.0` (required for Java 25); pinned with a checksum.
- Shadow plugin `8.3.6` → `9.4.2` (required for Gradle 9).
- gson `2.11.0` → `2.14.0`, commons-compress `1.27.1` → `1.28.0`,
  minio `8.5.10` → `9.0.3`, JUnit `5.11.3` → `6.1.0`. MinIO 9.x is a major API
  rewrite — `S3UploadProvider` was migrated (`io.minio.http.Method` →
  `io.minio.Http.Method`); the runtime S3 upload path isn't CI-verified.
- GitHub Actions bumped: `checkout` v4 → v7, `setup-java` v4 → v5,
  `gradle/actions` v4 → v6, `action-gh-release` v2 → v3.
- The release workflow now runs `./gradlew build` (tests gate the release) on Java 25
  and publishes both jars.

### Fixed

- `PolymathUploadProvider` no longer echoes an unbounded upstream response body into
  the console on a failed upload; error messages are collapsed to a single,
  length-capped snippet.
- Update-check manifest URL pointed at the non-existent `/main/` branch (default is
  `master`), so the check silently 404'd; both the config default and
  `UpdateChecker.DEFAULT_URL` now use `/master/`. The admin-facing "update available"
  link points to the SpigotMC page.

## [1.1.0] — 2026-04-16

Operator-experience release. Correctness of the merge engine is settled from
1.0.x; this one focuses on observability, validation, admin workflow, and
external integrations.

### Added

- **Per-file merge provenance log.** Every output path now records which pack
  wrote it, every contributor (in merge order), the merge strategy used, and
  whether it was a true merge vs single-contributor pass-through. Persisted
  to `output/<merged-pack>.zip.provenance.json` so restarts don't blank the
  state. Exposed via `PackMerger.getLastMergeProvenance()`.
- **`/pm inspect` command family.**
  - `/pm inspect` — top-line summary of the last merge
  - `/pm inspect <pack>` — per-pack detail (files won + files overridden)
  - `/pm inspect collisions` — list of every path touched by 2+ packs
  - `/pm inspect export` — writes the full plain-text report to
    `output/last-merge-report.txt`
- **Plugin API + Bukkit events.** New `sh.pcx.packmerger.api.PackMergerApi`
  interface accessible via `plugin.getApi()` with accessors for the current
  pack URL, SHA-1, last merge time, and provenance, plus `triggerMerge()`.
  Six new Bukkit events fire at their natural call sites:
  `PackMergeStartedEvent`, `PackMergedEvent`, `PackUploadedEvent`,
  `PackUploadFailedEvent`, `PackValidationFailedEvent`,
  `PackSentToPlayerEvent`. See `docs/api-example.java` for a sample listener.
  Tagged `@Experimental` until 1.2 to allow iteration in 1.1.x.
- **`pack_format` vs server-version guardrail.** PackValidator now warns
  when the merged pack's `pack_format` doesn't match the running Minecraft
  version. Honors `supported_formats` ranges (int, array, and
  `{min_inclusive, max_inclusive}` object shapes). Config key:
  `validation.pack-format-check` (`warn` | `error` | `off`).
- **Rollback on validation failure.** Merges now write to
  `<output>.new.zip` first; validation runs against the temp, and the
  previous output is kept live if validation errors trip. Fires
  `PackValidationFailedEvent` with `rolledBack=true` so listeners can
  page someone. Config: `validation.rollback-on-errors` (default `true`)
  and `validation.fail-on-warnings` (default `false`).
- **Orphan asset detection.** New `OrphanDetector` scans the merged output
  for unreferenced `.png` and `.ogg` files and reports them as warnings.
  Reference discovery covers models, item definitions, blockstates, atlases
  (with directory glob expansion), font providers, and `sounds.json`. Config:
  `validation.detect-orphans` (default `true`) and
  `validation.orphan-report-limit` (default `20`).
- **`/pm priority` in-game reordering.** No more YAML edit + reload:
  `/pm priority list|up|down|top|bottom|set <pack> <n>`. Persists via
  `plugin.saveConfig()` and triggers an immediate re-merge. Note: Bukkit's
  config writer doesn't preserve comments on save — documented tradeoff.
- **Profiles / presets.** New `profiles:` + `active-profile:` config keys
  let operators flip between whole pack compositions atomically via
  `/pm profile switch <name>`. When profiles aren't in use (or the section
  is absent), PackMerger falls back to root-level keys — fully backwards-
  compatible with 1.0.x configs.
- **Remote pack sources (HTTP).** New `remote-packs:` config section lets
  admins declare pack aliases whose contents come from an HTTP(S) URL.
  Packs are downloaded into `packs/.remote-cache/<alias>.zip` and
  recognized in the merge pipeline by their alias. ETag / Last-Modified
  caching, env-var substitution for secrets, bearer + basic auth, HTTPS-
  required-by-default. New `/pm fetch [alias]` command. Zero new runtime
  deps — uses the JDK's `HttpClient`.
- **S3-compatible upload provider.** New `provider: "s3"` setting with
  support for AWS S3, Cloudflare R2, and Backblaze B2 (all via the S3
  API). Bundled MinIO SDK, fully shaded + relocated. Supports
  content-addressed or stable key strategies, public-read or presigned
  (private) ACLs, and a retention policy for content-addressed buckets.
  Jar grows from ~270 KB to ~13 MB as a result; document in release notes.
- **Two new `PluginLogger` categories:** `validation()` (light purple) and
  `remote()` (blue).
- **Runtime dependency loader.** New `PackMergerLoader` downloads MinIO and
  its transitive closure from Maven Central on first enable, verifies each
  artifact against a build-time SHA-256, and loads them through an isolated
  classloader. Cached in `plugins/PackMerger/libraries/` for subsequent
  starts. Drops the shipped jar from ~13 MB to ~360 KB.
- **Update check.** On enable, the plugin polls `versions.json` in the repo
  to see if a newer release is available and surfaces it in the console +
  as a chat notice to admins on join. Advisory only — no auto-download.
  Config: `update-check.enabled` / `update-check.url`.
- **Actual Folia support.** Swapped every `BukkitScheduler` call to the
  right Folia scheduler (AsyncScheduler for periodic async work,
  GlobalRegionScheduler / entity scheduler where a specific thread matters).
  `PackDistributor.sendPack` now self-schedules on the player's region so
  callers don't have to know about threading. `plugin.yml` declares
  `folia-supported: true`. Paper behavior is unchanged — the scheduler
  APIs we use exist on both.

### Changed

- `PackMergeEngine.merge()` now accepts an optional target-file override
  (`merge(File)`), used by `PackMerger` for the write-to-temp-then-validate
  flow. Backwards-compatible: `merge()` keeps writing to
  `plugin.getOutputFile()`.
- Merge provenance moved from a single fixed-name file
  (`output/.merge-provenance.json`) to a sidecar keyed by output name
  (`output/<merged-pack>.zip.provenance.json`). Lets rollback rename the
  zip and sidecar together.
- `FileWatcher` now explicitly ignores dot-prefixed entries so the
  `.remote-cache/` subdirectory can't trigger cascading merges.

### Dependencies

- MinIO Java SDK 8.5.10 + its transitive closure (OkHttp, Okio, Kotlin
  stdlib, Jackson, BouncyCastle, Guava, Xerial Snappy) is now downloaded
  at runtime by the loader rather than shaded into the plugin jar.
  Manifest and SHA-256 digests live in `RuntimeDependencies.java`
  (auto-generated at build time from the `runtimeDownload` Gradle
  configuration).

## [1.0.5] — 2026-04-16

### Fixed

- **Language file merging.** `assets/<ns>/lang/*.json` translations now
  key-union across packs instead of last-write-wins, so stacking multiple
  plugins that localize messages into `en_us.json` no longer silently drops
  one side's strings.
- **`pack.mcmeta` `filter.block[]` preservation.** Filter entries (used
  to hide specific vanilla assets) are now concat-deduped by
  `namespace|path` across packs, mirroring the 1.0.4 `overlays.entries`
  fix.
- **CustomModelData collision warnings.** When two packs claim the same
  `custom_model_data` predicate on an item model, a warning is now logged
  at merge time with the file path and predicate — no more silent
  "my knife turned into a pistol" bug reports.

## [1.0.4] — 2026-04-16

### Fixed

- **`pack.mcmeta` overlays preservation.** `overlays.entries[]` declarations
  (used by QualityArmory and other packs for version-specific asset
  directories) are now concat-deduped by `directory` instead of being
  dropped when a higher-priority pack's `pack.mcmeta` wins.

## [1.0.3] — 2026-04-16

### Added

- **Path-specific merge strategies.** The JSON merger is split into
  dedicated strategies per Minecraft format: models, blockstates, atlases,
  item definitions, equipment, sounds, font, pack.mcmeta.
- **QualityArmory compatibility.** Item-model `overrides` arrays from
  multiple packs now concat-dedup by predicate instead of being replaced,
  so CustomModelData entries survive.
- **1.21.4 item definitions.** `assets/<ns>/items/*.json` files are now
  recognised and deep-merged.
- **1.21.2+ equipment models.** `assets/<ns>/equipment/*.json` deep-merged
  per slot.
- **1.19.3+ texture atlases.** `sources` arrays concatenated with
  structural dedup instead of replaced.
- **Font providers.** `assets/<ns>/font/*.json` `providers` arrays now
  concat so custom chat-icon packs compose cleanly.
- Comprehensive strategy-level test suite.

## [1.0.2] — earlier

- Fix `saveResource` warning when `messages_en.yml` already exists.

## [1.0.1] — earlier

- Externalize player-facing messages into configurable `messages_en.yml`.
- Add `PluginLogger` with colored categories and configurable log levels.
