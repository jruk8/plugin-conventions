# plugin-conventions

Shared Gradle convention plugin and reusable GitHub Actions workflows for
jruk8 Paper/Bukkit Minecraft plugins.

This repository encapsulates the build configuration that was previously
duplicated across every plugin repository: Java 25 toolchain, Checkstyle,
Shadow + bstats relocation, axion-release versioning, repository
declarations, dependency defaults, `plugin.yml` resource expansion, and the
full publish pipeline (Modrinth, GitHub Releases, snapshot management).

## Versioning

Versions are derived from git tags via [axion-release](https://github.com/allegro/axion-release).

- **Stable releases** are tagged `v<version>` (e.g. `v1.0.0`).
- **Snapshots** are built from `main` and suffixed with `-SNAPSHOT.<GITHUB_RUN_NUMBER>`
  in CI.

The convention plugin is published to **GitHub Packages** (Maven registry).
It is **not** published to Modrinth — only the consuming plugins are.

## Consuming the convention plugin

### 1. `settings.gradle`

Add a `pluginManagement` block so Gradle can resolve the convention plugin
from GitHub Packages:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = 'https://maven.pkg.github.com/jruk8/plugin-conventions'
            credentials {
                username = System.getenv('GITHUB_ACTOR') ?: providers.gradleProperty('gpr.user').getOrElse('jruk8')
                password = System.getenv('GITHUB_TOKEN') ?: providers.gradleProperty('gpr.key').getOrElse('')
            }
        }
    }
}

rootProject.name = 'YourPlugin'
```

### 2. `build.gradle`

Replace the entire build file with a single plugin declaration:

```groovy
plugins {
    id 'com.jruk8.plugin-conventions' version '1.0.0'
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
    uses: jruk8/plugin-conventions/.github/workflows/build.yml@v1.0.0
```

```yaml
# .github/workflows/publish.yml
name: Publish

on:
  push:
    branches: [main]
    tags: ['v*']

jobs:
  publish:
    uses: jruk8/plugin-conventions/.github/workflows/publish.yml@v1.0.0
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

### CI (GitHub Actions)

The reusable workflows use the default `GITHUB_TOKEN` for authentication.
No additional secrets are needed for resolving the convention plugin.

### Local development

To resolve the convention plugin from GitHub Packages locally, create a
GitHub Personal Access Token (PAT) with the `read:packages` scope, then add
it to your `~/.gradle/gradle.properties`:

```properties
gpr.user=jruk8
gpr.key=ghp_your_personal_access_token_here
```

## Cutting a release

1. Push commits to `main`.
2. Create and push a tag:

```bash
git tag v1.0.1
git push origin v1.0.1
```

The `release.yml` workflow in this repo will:
- Build and publish the convention plugin to GitHub Packages.
- Create a GitHub Release with the changelog.
- Clean up any previous snapshot release.

After the release is published, update the version reference in all
consuming plugins' `build.gradle` and workflow `uses:` lines.

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
