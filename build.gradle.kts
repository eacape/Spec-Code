import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.GradleException
import java.util.Locale

buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.3")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

apply(plugin = "org.jetbrains.kotlinx.kover")

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    maven("https://maven.aliyun.com/repository/public")
    maven("https://maven.aliyun.com/repository/central")
    maven("https://maven.aliyun.com/repository/google")
    maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.ktor:ktor-client-core:2.3.12") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation("io.ktor:ktor-client-cio:2.3.12") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        create(
            providers.gradleProperty("platformType").get(),
            providers.gradleProperty("platformVersion").get(),
        )

        val bundledPluginIds = providers.gradleProperty("platformBundledPlugins").orNull
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
        bundledPluginIds.forEach(::bundledPlugin)

        val marketplacePluginIds = providers.gradleProperty("platformPlugins").orNull
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            .orEmpty()
        marketplacePluginIds.forEach(::plugin)

        testFramework(TestFrameworkType.Platform)
    }
}

val phase3PackagePrefixes = listOf(
    "com.eacape.speccodingplugin.worktree",
    "com.eacape.speccodingplugin.session",
    "com.eacape.speccodingplugin.window",
    "com.eacape.speccodingplugin.ui.history",
    "com.eacape.speccodingplugin.ui.worktree",
)

kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName").get()
        version = providers.gradleProperty("pluginVersion").get()
        description = """
            Spec-Driven Development (SDD) AI coding workflow for JetBrains IDEs.
            <p>
            Move AI-assisted changes from ad-hoc prompts to Spec-Driven Development (SDD) workflows with grounded context and reviewable history.
            </p>
            <ul>
              <li>Stage-based workflows for requirements, design, tasks, implementation, verification, and archive.</li>
              <li>Code graph, related-file discovery, source attachments, and smart context trimming for better prompt grounding.</li>
              <li>Editor gutter icons and inline hints for AI changes and Spec associations.</li>
              <li>History diff, delta comparison, changeset timeline, and rollback-oriented review.</li>
              <li>Claude CLI / Codex CLI integration with hooks, skills, worktrees, and operation modes.</li>
            </ul>
            <p>
            面向 JetBrains IDE 的规范驱动开发（SDD）AI 编码工作流插件
            </p>
            <p>
            将 AI 辅助改动从零散提示词收敛为带有工作流阶段、上下文收敛和可审查历史的规范驱动开发（SDD）结构化流程
            </p>
            <ul>
              <li>以 requirements、design、tasks、implementation、verification、archive 为核心的阶段化工作流。</li>
              <li>通过代码图谱、相关文件发现、来源附件和智能上下文裁剪提升提示词 grounding。</li>
              <li>在编辑器中提供 AI 变更与 Spec 关联的 gutter 图标和行内提示。</li>
              <li>提供历史对比、Delta 对比、changeset timeline 和面向回退的审查能力。</li>
              <li>集成 Claude CLI / Codex CLI，以及 hooks、skills、worktrees 和 operation modes。</li>
            </ul>
        """.trimIndent()
        changeNotes = """
            <ul>
              <li>First beta release of Spec Code for JetBrains IDEs with Spec-Driven Development (SDD) workflow support.</li>
              <li>Includes structured Spec-Driven Development (SDD) workflows, code graph grounding, editor hints, and review/history surfaces.</li>
              <li>Includes Claude CLI / Codex CLI integration, operation modes, hooks, worktrees, prompt templates, skills, and session history.</li>
            </ul>
            <ul>
              <li>Spec Code 面向 JetBrains IDE 的首个 beta 版本，支持规范驱动开发（SDD）工作流。</li>
              <li>当前版本包含结构化规范驱动开发（SDD）工作流、代码图谱收敛、编辑器提示以及历史和审查视图。</li>
              <li>当前版本同时提供 Claude CLI / Codex CLI 集成，以及 operation modes、hooks、worktrees、prompt templates、skills 和 session history。</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild").get()
            untilBuild = providers.gradleProperty("pluginUntilBuild").get()
        }
    }
}

tasks {
    withType<Test> {
        useJUnitPlatform()
    }

    val phase1AcceptanceTest by registering(Test::class) {
        group = "verification"
        description = "Run Phase 1 acceptance-oriented automated test subset"

        useJUnitPlatform()
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath

        filter {
            includeTestsMatching("com.eacape.speccodingplugin.llm.*")
            includeTestsMatching("com.eacape.speccodingplugin.core.OperationModeManagerTest")
            includeTestsMatching("com.eacape.speccodingplugin.core.SpecCodingProjectServiceTest")
            includeTestsMatching("com.eacape.speccodingplugin.prompt.*")
            includeTestsMatching("com.eacape.speccodingplugin.skill.*")
            includeTestsMatching("com.eacape.speccodingplugin.window.GlobalConfigSyncServiceTest")
            includeTestsMatching("com.eacape.speccodingplugin.i18n.*")
            includeTestsMatching("com.eacape.speccodingplugin.acceptance.phase1.*")
        }
    }

    register("phase1Acceptance") {
        group = "verification"
        description = "Run Phase 1 acceptance automated checks and build plugin package"
        dependsOn(phase1AcceptanceTest, "buildPlugin")
    }

    val phase3CoverageReport by registering {
        group = "verification"
        description = "Generate Phase 3 Kover coverage reports"

        dependsOn("koverHtmlReport", "koverXmlReport")
    }

    val phase3CoverageVerify by registering {
        group = "verification"
        description = "Verify Phase 3 line coverage is at least 80% (from Kover XML)"

        dependsOn("koverXmlReport")

        doLast {
            val xmlCandidates = listOf(
                layout.buildDirectory.file("reports/kover/report.xml").get().asFile,
                layout.buildDirectory.file("reports/kover/xml/report.xml").get().asFile,
            )
            val xmlFile = xmlCandidates.firstOrNull { it.exists() }
                ?: throw GradleException("Kover XML report not found under build/reports/kover")

            val documentBuilder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val document = documentBuilder.parse(xmlFile)
            val packages = document.getElementsByTagName("package")

            var lineCovered = 0L
            var lineMissed = 0L

            for (index in 0 until packages.length) {
                val packageElement = packages.item(index) as org.w3c.dom.Element
                val packageName = packageElement.getAttribute("name").replace('/', '.')
                if (phase3PackagePrefixes.none { packageName.startsWith(it) }) continue

                val children = packageElement.childNodes
                for (childIndex in 0 until children.length) {
                    val node = children.item(childIndex)
                    if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE || node.nodeName != "counter") continue
                    val counter = node as org.w3c.dom.Element
                    if (counter.getAttribute("type") != "LINE") continue
                    lineMissed += counter.getAttribute("missed").toLong()
                    lineCovered += counter.getAttribute("covered").toLong()
                    break
                }
            }

            val totalLines = lineCovered + lineMissed
            if (totalLines <= 0L) {
                throw GradleException("No Phase 3 line counters found in Kover report: ${xmlFile.absolutePath}")
            }

            val ratio = lineCovered.toDouble() / totalLines.toDouble()
            val percent = ratio * 100.0
            logger.lifecycle(
                "Phase 3 line coverage: {}% (covered={}, missed={})",
                "%.2f".format(Locale.US, percent),
                lineCovered,
                lineMissed,
            )

            if (ratio < 0.80) {
                throw GradleException(
                    "Phase 3 line coverage %.2f%% is below threshold 80%%"
                        .format(Locale.US, percent)
                )
            }
        }
    }

    register("phase3Coverage") {
        group = "verification"
        description = "Generate and verify Phase 3 coverage (run test with --tests filters first)"
        dependsOn(phase3CoverageReport, phase3CoverageVerify)
    }
}
