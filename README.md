# plugin-conventions

Shared Gradle convention plugin and reusable GitHub Actions workflows for
jruk8 Paper/Bukkit Minecraft plugins.

This repository encapsulates the build configuration previously
duplicated across every plugin repository: Java 25 toolchain, Checkstyle,
Shadow + bstats relocation, axion-release versioning driven by
[Conventional Commits](https://www.conventionalcommits.org/), automated
`CHANGELOG.md` generation via [git-cliff](https://git-cliff.org),
repository declarations, dependency defaults, `plugin.yml` resource
expansion, and reusable GitHub Actions workflows for publishing consuming
plugins (Modrinth, GitHub Releases, snapshot management).

## Versioning

Versions are derived from git tags via [axion-release](https://github.com/allegro/axion-release).

- **Stable releases** are tagged `v<version>` (e.g. `v1.1.5`).
- **Snapshots** are built from `main` and suffixed with `-SNAPSHOT.<GITHUB_RUN_NUMBER>`
  in CI.
- **The version bump (major/minor/patch) is determined automatically**
  from commit messages since the last tag, following the
  [Conventional Commits](https://www.conventionalcommits.org/) spec:
  - `fix: ...` → patch bump
  - `feat: ...` → minor bump
  - `feat!: ...` or a `BREAKING CHANGE:` footer → major bump
  - anything else (`chore:`, `docs:`, `ci:`, etc.) with no qualifying
    commit → defaults to a patch bump
  - the highest-priority match wins if a commit set contains multiple
    types (e.g. a `feat:` and a `fix:` together bump minor, not patch)
  - tag lookup only considers tags that are ancestors of the current
    commit (`--merged HEAD`), so repos with multiple long-lived branches
    (e.g. a `release/1.x` branch alongside `main`) each compute their
    bump independently without cross-branch tags leaking in
- To force a specific bump regardless of commit history, pass it
  explicitly:

```bash
  ./gradlew release "-Prelease.versionIncrementer=incrementMajor"   # or minor / patch
```

This is a shorthand for axion's own `-Prelease.versionIncrementer=incrementMajor`
and always takes precedence over the automatic detection.

## Changelog

`CHANGELOG.md` is generated and maintained automatically by
[git-cliff](https://git-cliff.org), grouped by Conventional Commit type
(Features, Bug Fixes, Breaking Changes, etc.). On every real release the
publish workflow regenerates the entry for the new tag, prepends it to
`CHANGELOG.md`, and commits the update back to `main`. The same generated
notes are used as the body of the GitHub Release and the Modrinth changelog.

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

### 4. `cliff.toml`

Add a `cliff.toml` at the repo root so the publish workflow can generate
release notes and maintain `CHANGELOG.md` for your plugin. See this
repo's own [`cliff.toml`](./cliff.toml) as a starting template — the
commit-type groupings should match across all jruk8 repos for consistency.

### 5. GitHub Actions workflows

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
>
> The calling job must declare `permissions: { contents: write, packages: read }`
> explicitly — reusable workflows can never be granted more permissions
> than the caller has, regardless of what `publish.yml` itself requests.

### 6. Dependency updates (Dependabot)

Consuming repos should add a `.github/dependabot.yml` covering both the
`gradle` and `github-actions` ecosystems. Commit message prefixes matter
here since they feed the automatic version bump above — use `fix:` for
Gradle dependency bumps (they count as a patch release) and `chore:` for
GitHub Actions bumps (CI-only, no release triggered). See
[this repo's `dependabot.yml`](./.github/dependabot.yml) for the reference
config, including grouping to avoid PR noise.

### 7. Remove local checkstyle config

Delete `config/checkstyle/checkstyle.xml` from your plugin repo — the
checkstyle configuration is now bundled inside the convention plugin jar.

## Authentication

JitPack builds and serves artifacts from public git repositories without
requiring any authentication. No tokens or credentials are needed to
resolve the convention plugin.

## Cutting a release

1. Push commits to `main` using Conventional Commit messages (`feat:`,
   `fix:`, `feat!:`/`BREAKING CHANGE:`, etc.) — these drive the automatic
   version bump described in [Versioning](#versioning).
2. Run the release task:

```bash
./gradlew release
```

This computes the next version from the commits since the last tag,
creates the `v<version>` tag, and pushes it. To override the computed
bump, use `./gradlew release -Pbump=major` (or `minor`/`patch`).

Once the tag lands, `release.yml` in this repo (which delegates to the
same `publish.yml` reusable workflow consuming repos use) will:
- Build the convention plugin.
- Generate release notes and prepend them to `CHANGELOG.md` via git-cliff.
- Create a GitHub Release with the generated changelog.
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
- Configures `shadowJar` to include all runtime dependencies (including
  any the consuming plugin declares) and relocates bstats into the plugin's
  package, and disables the plain `jar` task.
- Configures `processResources` to expand `plugin.yml` with values from
  `gradle.properties` and the computed version.
- Configures axion-release with `v` tag prefix, CI-aware snapshot
  suffixes, and a custom version incrementer that determines major/minor/patch
  bumps from Conventional Commits since the last branch-ancestor tag
  (overridable via `-Pbump=major|minor|patch`).
- Validates that all seven required properties are present.