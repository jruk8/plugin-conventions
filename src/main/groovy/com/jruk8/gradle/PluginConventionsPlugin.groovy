package com.jruk8.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.util.jar.JarFile

/**
 * Shared build conventions for jruk8 Paper/Bukkit plugins.
 */
class PluginConventionsPlugin implements Plugin<Project> {

    private static final List<String> REQUIRED_PROPERTIES = [
            'pluginGroup', 'pluginName', 'pluginMain', 'pluginAuthor',
            'pluginDescription', 'pluginWebsite', 'paperApiVersion'
    ]

    private static final String MOCKBUKKIT_DEPENDENCY = 'com.github.MockBukkit:MockBukkit:v26.1.2-SNAPSHOT'

    @Override
    void apply(Project project) {
        validateProperties(project)

        // --- Apply plugins ---
        project.pluginManager.apply('java')
        project.pluginManager.apply('checkstyle')
        project.pluginManager.apply('com.gradleup.shadow')
        project.pluginManager.apply('pl.allegro.tech.build.axion-release')

        String pluginGroup = project.property('pluginGroup')
        project.group = pluginGroup

        // --- Axion versioning ---
        def scmVersionExt = project.extensions.getByName('scmVersion')
        Closure scmVersionConfig = {
            tag {
                prefix = 'v'
                versionSeparator = ''
            }
            snapshotCreator { version, position -> /* unchanged */ }

            versionIncrementer { context ->
                Process tagProc = ['git', 'tag', '--sort=-version:refname', '--list', 'v*', '--merged', 'HEAD', '--no-contains', 'HEAD']
                        .execute(null, project.projectDir)
                tagProc.waitFor()
                String previousTag = tagProc.text.readLines().find { it?.trim() }?.trim()

                String range = previousTag ? "${previousTag}..HEAD" : "HEAD"

                Process logProc = ['git', 'log', '--no-merges', '--pretty=format:%s%n%b%n===END===\n', range]
                        .execute(null, project.projectDir)
                logProc.waitFor()
                String commits = logProc.text

                boolean isMajor = commits =~ /(?m)^\w+(\([^)]*\))?!:/ || commits.contains('BREAKING CHANGE:') || commits.contains('BREAKING-CHANGE:')
                boolean isMinor = commits =~ /(?m)^feat(\([^)]*\))?:/
                boolean isPatch = commits =~ /(?m)^fix(\([^)]*\))?:/

                if (isMajor) {
                    return context.currentVersion.incrementMajorVersion()
                } else if (isMinor) {
                    return context.currentVersion.incrementMinorVersion()
                } else if (isPatch) {
                    return context.currentVersion.incrementPatchVersion()
                } else {
                    return context.currentVersion.incrementPatchVersion()
                }
            }
        }
        scmVersionConfig.delegate = scmVersionExt
        scmVersionConfig.resolveStrategy = Closure.DELEGATE_FIRST
        scmVersionConfig()

        project.version = scmVersionExt.version

        if (isLocalDirtyBuild(project) && !project.version.toString().contains('-SNAPSHOT')) {
            project.version = "${project.version}-SNAPSHOT"
        }

        // --- Java toolchain ---
        def javaExt = project.extensions.getByName('java')
        javaExt.toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }

        // --- Repositories ---
        project.repositories {
            mavenCentral()
            maven { url = 'https://repo.papermc.io/repository/maven-public/' }
            maven { url = 'https://maven.enginehub.org/repo/' }
            maven { url = 'https://jitpack.io' }
        }

        // --- Dependencies ---
        String paperApiVersion = project.property('paperApiVersion')

        project.dependencies {
            compileOnly "io.papermc.paper:paper-api:${paperApiVersion}"
            implementation 'org.bstats:bstats-bukkit:3.2.1'
            testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
            testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.10.2'

            // Testing
            testImplementation 'org.mockito:mockito-core:5.23.0'
            testImplementation 'org.mockito:mockito-junit-jupiter:5.23.0'
            testImplementation MOCKBUKKIT_DEPENDENCY
        }

        // Resolve Paper API for testImplementation lazily via resolution strategy
        // to avoid inspecting configurations before they are sealed.
        project.configurations.named('testCompileClasspath').configure {
            resolutionStrategy.eachDependency { details ->
                if (details.requested.group == 'io.papermc.paper' && details.requested.name == 'paper-api') {
                    String extractedVersion = getMockBukkitPaperVersion(project)
                    if (extractedVersion) {
                        details.useVersion(extractedVersion)
                    }
                }
            }
        }

        // --- Checkstyle ---
        def checkstyleExt = project.extensions.getByName('checkstyle')
        checkstyleExt.toolVersion = '10.17.0'

        File checkstyleFile = new File(project.layout.buildDirectory.get().asFile, 'checkstyle/checkstyle.xml')
        checkstyleExt.configFile = checkstyleFile

        def extractCheckstyleConfigTask = project.tasks.register('extractCheckstyleConfig') {
            outputs.file(checkstyleFile)
            doLast {
                checkstyleFile.parentFile.mkdirs()
                InputStream resource = PluginConventionsPlugin.class.getResourceAsStream('/checkstyle.xml')
                if (resource == null) {
                    throw new GradleException('Bundled checkstyle.xml not found on plugin classpath.')
                }
                resource.withStream { input ->
                    checkstyleFile.withOutputStream { output ->
                        output << input
                    }
                }
            }
        }

        project.tasks.named('checkstyleMain').configure { dependsOn extractCheckstyleConfigTask }
        project.tasks.named('checkstyleTest').configure { dependsOn extractCheckstyleConfigTask }

        // --- Compile options ---
        project.tasks.withType(JavaCompile).configureEach {
            options.encoding = 'UTF-8'
            options.release = 25
        }

        // --- Shadow jar (bstats relocation) ---
        project.plugins.withId('com.gradleup.shadow') {
            project.tasks.named('shadowJar').configure {
                configurations = [project.configurations.runtimeClasspath]
                dependencies {
                    include(dependency('org.bstats:.*:.*'))
                }
                relocate('org.bstats', pluginGroup)
                archiveClassifier = ''
            }
            project.tasks.named('jar').configure {
                enabled = false
            }
        }

        // --- Test ---
        project.tasks.named('test').configure {
            useJUnitPlatform()
        }

        // --- Check depends on checkstyle ---
        project.tasks.named('check').configure {
            dependsOn 'checkstyleMain', 'checkstyleTest'
        }

        // --- processResources: expand gradle.properties into plugin.yml ---
        project.tasks.named('processResources').configure {
            String paperApi = project.property('paperApiVersion')
            Map<String, Object> pluginProperties = [
                    version    : project.version,
                    name       : project.property('pluginName'),
                    main       : project.property('pluginMain'),
                    apiVersion : paperApi.split('\\.build\\.')[0],
                    author     : project.property('pluginAuthor'),
                    description: project.property('pluginDescription'),
                    website    : project.property('pluginWebsite')
            ]
            inputs.properties(pluginProperties)
            filesMatching('plugin.yml') {
                expand(pluginProperties)
            }
        }
    }

    /**
     * Resolves MockBukkit isolated inside a detached configuration to read the manifest
     * attribute without prematurely observing or locking project-level configurations.
     */
    private static String getMockBukkitPaperVersion(Project project) {
        try {
            def detached = project.configurations.detachedConfiguration(
                    project.dependencies.create(MOCKBUKKIT_DEPENDENCY)
            )
            File mockbukkitJar = detached.files.find { it.name.toLowerCase().contains('mockbukkit') }

            if (mockbukkitJar) {
                JarFile jarFile = new JarFile(mockbukkitJar)
                try {
                    String paperVersion = jarFile.manifest?.mainAttributes?.getValue('Paper-Version')
                    if (paperVersion) {
                        return paperVersion
                    }
                } finally {
                    jarFile.close()
                }
            }
        } catch (Exception ignored) {
            // Fallback if network/offline failure occurs during detached resolution
        }
        return '26.2'
    }

    private static boolean isLocalDirtyBuild(Project project) {
        boolean isCiBuild = System.getenv('GITHUB_RUN_NUMBER') != null
        if (isCiBuild) {
            return false
        }
        try {
            Process process = ['git', 'status', '--porcelain'].execute(null, project.projectDir)
            process.waitFor()
            return process.text.trim().length() > 0
        } catch (IOException ignored) {
            return false
        }
    }

    private static void validateProperties(Project project) {
        for (String prop : REQUIRED_PROPERTIES) {
            if (!project.hasProperty(prop)) {
                throw new GradleException(
                        "Missing required property '${prop}' in gradle.properties. " +
                                "See the plugin-conventions README for setup instructions."
                )
            }
        }
    }
}