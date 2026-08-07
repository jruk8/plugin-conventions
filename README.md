# plugin-conventions

Shared Gradle convention plugin and reusable GitHub Actions workflows for
jruk8 Paper/Bukkit Minecraft plugins.

This repository encapsulates the build configuration previously
duplicated across every plugin repository: Java 25 toolchain, Checkstyle,
Shadow + bstats relocation, axion-release versioning, repository
declarations, dependency defaults, `plugin.yml` resource expansion, and
reusable GitHub Actions workflows for publishing consuming plugins
(Modrinth, GitHub Releases, snapshot management).

## Versioning

Versions are derived from git tags via [axion-release](https://github.com/allegro/axion-release).

- **Stable releases** are tagged `v<version>` (e.g. `v1.1.5`).
- **Snapshots** are built from `main` and suffixed with `-SNAPSHOT.<GITHUB_RUN_NUMBER>`
  in CI.

The convention plugin is distributed via **JitPack**, which builds
artifacts on-demand from git tags. It is **not** published to Modrinth —
only the consuming plugins are.

> **Note on the plugin `version` field:** unlike most Gradle plugins, the
> `version` in the `plugins {}` block below must include the `v` prefix
> (e.g. `version 'v1.1.5'`, not `version '1.1.5'`). This matches the raw
> git tag name and is required for JitPack to resolve the plugin marker
> artifact correctly — this is intentional, not a typo.

## Consuming the convention plugin

### 1. `settings.gradle`

Add a `pluginManagement` block so Gradle can resolve the convention plugin
from JitPack:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = 'https://jitpack.io' }
    }
}

rootProject.name = 'YourPlugin'
```

### 2. `build.gradle`

Replace the entire build file with a single plugin declaration:

```groovy
plugins {
    id 'com.github.jruk8.plugin-conventions' version 'v1.1.5'
}
```

### 3. `gradle.properties`

Keep the seven plugin-specific properties in your `gradle.properties`:

```properties
pluginGroup=com.jruk8
pluginName=YourPlugin
pluginMain=com.jruk8.yourplugin.YourPluginPlugin
pluginAuthor=jruk8
pluginDescription=Your plugin description
pluginWebsite=https://github.com/jruk8/YourPlugin
paperApiVersion=26.2.build.+
```

| Property             | Description                                              |
|----------------------|----------------------------------------------------------|
| `pluginGroup`        | Base package / shadow relocation target                  |
| `pluginName`         | Human-readable name injected into `plugin.yml`           |
| `pluginMain`         | Fully-qualified main class                               |
| `pluginAuthor`       | Author injected into `plugin.yml`                        |
| `pluginDescription`  | Description injected into `plugin.yml`                   |
| `pluginWebsite`      | Website injected into `plugin.yml`                       |
| `paperApiVersion`    | Paper API dependency version (e.g. `26.2.build.+`)       |

### 4. GitHub Actions workflows

Replace your `.github/workflows/build.yml` and `publish.yml` with thin
callers that reference the reusable workflows by tag:

```yaml
# .github/workflows/build.yml
name: Build

on: [push, pull_request]

jobs:
  build:
    uses: jruk8/plugin-conventions/.github/workflows/build.yml@v1.1.5
```

```yaml
# .github/workflows/publish.yml
name: Publish

on:
  push:
    branches: [main]
    tags: ['v*']

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  publish:
    permissions:
      contents: write
      packages: read
    uses: jruk8/plugin-conventions/.github/workflows/publish.yml@v1.1.5
    secrets: inherit
```

> **Note:** The `publish.yml` reusable workflow expects the following
> repository variables and secrets to be configured on the consuming repo:
> - **Variables:** `MODRINTH_ID`, `MODRINTH_LOADERS`, `MODRINTH_GAME_VERSIONS`
> - **Secrets:** `MODRINTH_TOKEN`, `GH_RELEASE_TOKEN`

### 5. Remove local checkstyle config

Delete `config/checkstyle/checkstyle.xml` from your plugin repo — the
checkstyle configuration is now bundled inside the convention plugin jar.

## Authentication

JitPack builds and serves artifacts from public git repositories without
requiring any authentication. No tokens or credentials are needed to
resolve the convention plugin.

## Cutting a release

1. Push commits to `main`.
2. Create and push a tag:

```bash
git tag v1.1.6
git push origin v1.1.6
```

The `release.yml` workflow in this repo will:
- Build the convention plugin.
- Create a GitHub Release with the changelog.
- Clean up any previous snapshot release.

JitPack automatically detects the new tag and builds the artifact on-demand.
After the release is published, update the version reference in all
consuming plugins' `build.gradle` (with the `v` prefix, see note above)
and workflow `uses:` lines (also with the `v` prefix — this one follows
normal git ref conventions).

## What the convention plugin does

When applied to a project, the plugin:

- Applies `java`, `checkstyle`, `com.gradleup.shadow`, and
  `pl.allegro.tech.build.axion-release`.
- Configures a Java 25 toolchain and `options.release = 25`.
- Declares repositories: Maven Central, PaperMC, and EngineHub.
- Adds dependencies: Paper API (`compileOnly`), bstats (`implementation`),
  JUnit 5 (`testImplementation`).
- Configures Checkstyle with the bundled `checkstyle.xml` (LineLength 120,
  AvoidStarImport, UnusedImports, NeedBraces).
- Configures `shadowJar` to relocate bstats into the plugin's package and
  disables the plain `jar` task.
- Configures `processResources` to expand `plugin.yml` with values from
  `gradle.properties` and the computed version.
- Configures axion-release with `v` tag prefix and CI-aware snapshot
  suffixes.
- Validates that all seven required properties are present.