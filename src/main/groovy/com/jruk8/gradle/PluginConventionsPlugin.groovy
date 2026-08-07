package com.jruk8.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

/**
 * Shared build conventions for jruk8 Paper/Bukkit plugins.
 *
 * <p>Encapsulates the common build configuration that was previously duplicated across plugin
 * repositories: Java 25 toolchain, checkstyle, shadow + bstats relocation, axion-release
 * versioning, repository declarations, dependency defaults, and {@code plugin.yml} resource
 * expansion from {@code gradle.properties}.
 *
 * <p>A consuming plugin only needs to declare this plugin in its {@code build.gradle} and keep
 * the seven plugin-specific properties in {@code gradle.properties}:
 * <ul>
 *   <li>{@code pluginGroup} &mdash; base package / shadow relocation target
 *   <li>{@code pluginName} &mdash; human-readable name injected into {@code plugin.yml}
 *   <li>{@code pluginMain} &mdash; fully-qualified main class
 *   <li>{@code pluginAuthor} &mdash; author injected into {@code plugin.yml}
 *   <li>{@code pluginDescription} &mdash; description injected into {@code plugin.yml}
 *   <li>{@code pluginWebsite} &mdash; website injected into {@code plugin.yml}
 *   <li>{@code paperApiVersion} &mdash; Paper API dependency version (e.g. {@code 26.2.build.+})
 * </ul>
 */
class PluginConventionsPlugin implements Plugin<Project> {

    private static final List<String> REQUIRED_PROPERTIES = [
            'pluginGroup', 'pluginName', 'pluginMain', 'pluginAuthor',
            'pluginDescription', 'pluginWebsite', 'paperApiVersion'
    ]

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
            snapshotCreator { version, position ->
                String ciBuild = System.getenv('GITHUB_RUN_NUMBER')
                String suffix = ciBuild ?: ''
                return suffix ? ("-SNAPSHOT." + suffix) : "-SNAPSHOT"
            }
        }
        scmVersionConfig.delegate = scmVersionExt
        scmVersionConfig.resolveStrategy = Closure.DELEGATE_FIRST
        scmVersionConfig()

        project.version = scmVersionExt.version

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
        }

        // --- Dependencies ---
        String paperApiVersion = project.property('paperApiVersion')
        project.dependencies {
            compileOnly "io.papermc.paper:paper-api:${paperApiVersion}"
            implementation 'org.bstats:bstats-bukkit:3.2.1'
            testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
            testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.10.2'
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
                    version   : project.version,
                    name      : project.property('pluginName'),
                    main      : project.property('pluginMain'),
                    apiVersion: paperApi.split('\\.build\\.')[0],
                    author    : project.property('pluginAuthor'),
                    description: project.property('pluginDescription'),
                    website   : project.property('pluginWebsite')
            ]
            inputs.properties(pluginProperties)
            filesMatching('plugin.yml') {
                expand(pluginProperties)
            }
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