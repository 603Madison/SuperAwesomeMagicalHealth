# Notes for Claude

## The project

- `shared/`: the API's models (kotlinx.serialization), used by the server and the Android app.
  The web app's copies are in `web/src/lib/types.ts`; keep them in step. `docs/API.md` lists the
  endpoints.
- `server/`: Kotlin + Ktor + SQLite, built like SuperAwesomeMagicalMusic's server (auth, sessions,
  break-glass admin, OIDC with PKCE, `ApiError`, `Database.tx`). Migrations are in
  `db/Schema.kt`: never edit a released one, add another. Scores are pure functions in
  `insights/Scoring.kt`; `DayService` caches each day's `DayStats` in `day_cache`, so **every
  write path must call `days.changed…`** for the days it touches (raise `DayStats.VERSION` when
  the way days are worked out changes). The MCP server is `mcp/Mcp.kt`.
- `web/`: React + Vite + TypeScript, built into the server's jar.
- `android/`: Jetpack Compose, signs in to the server; device conduits in `devices/`, sync in
  `sync/`.

## Design

Match the family (SuperAwesomeMagicalMusic, Weather, Cockpit Manager): Lexend, cool slate
neutrals, rounded cards (white in light themes), a soft aurora glow at the top, Aurora (purple
and green) by default with Violet, Ocean and Ember, light and dark, and a Bold text option.
Charts use the `--c1/--c2/--c3` tokens, whose values per theme and scheme were checked for
colour-blind separation; check new ones the same way. Copy is short, warm and plain.

The calorie formula is chosen by the person ("the female formula", "the male formula", "the
average of both"), never inferred: keep it that way.

## Checks before pushing

```bash
cd web && npm ci && npm run build
```

```bash
./gradlew :server:test :android:testDebugUnitTest :android:lintDebug :android:assembleDebug
```

Look at the PNGs the Android `ScreenshotTest` writes to `android/build/outputs/screenshots/`
after UI changes, and screenshot the web app (Playwright) after web changes.

## Releases

One version for everything: `versionName`/`versionCode` in `android/build.gradle.kts` (the
server and image read it). Bump both, write `release-notes/vX.Y.Z.md`, push, and run the
**Release** workflow with `version: vX.Y.Z`: it builds the signed APK, publishes the GitHub
release and pushes the image to `ghcr.io/603madison/superawesomemagicalfitness`.

The APK must always be signed with the same key, or installed copies can't update. The key is
not in git: CI reads `RELEASE_KEYSTORE_BASE64` and `RELEASE_KEYSTORE_PASSWORD` from repository
secrets, local builds read `keystore/release.properties` (git-ignored). Never generate a
replacement for an existing key. To make the first one (once):

```bash
keytool -genkeypair -v -keystore release.jks -alias fitness -keyalg RSA -keysize 4096 -validity 36500
```

```bash
base64 -w0 release.jks
```

## Building in a Claude cloud sandbox

- Android SDK: command-line tools in `/opt/android-sdk/cmdline-tools/latest`, then
  `sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`, and
  `sdk.dir=/opt/android-sdk` in `local.properties` (git-ignored).
- `repo.maven.apache.org` answers HTTP 429: serve Maven Central from Google's mirror with an init
  script in `~/.gradle/init.d/` (see SuperAwesomeMagicalMusic's CLAUDE.md for the script).
- Robolectric: add `-Drobolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2`
  to `JAVA_TOOL_OPTIONS`.
- Server-only Gradle runs: `SAMF_SERVER_ONLY=1` leaves the Android module out.
- Container builds: `dockerd` can be started in the background. Pull base images from
  `mirror.gcr.io/library/…` and tag them as `docker.io/library/…`. Builds need `--network host`,
  and every stage that downloads needs the sandbox's CA bundle (`/root/.ccr/ca-bundle.crt`;
  import **each** certificate in it into Java's cacerts, not just the first) and Java proxy
  settings: do that in a scratch copy of the Containerfile, never in the real one.
