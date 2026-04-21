pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val localAndroidStudioReleasesListUri = file("gradle/android-studio-releases-list.xml").toURI().toString()

// IntelliJ Platform Gradle Plugin 2.2.1 eagerly probes the Android Studio
// releases listing during plugin application. In some proxy/corporate
// environments the default jb.gg -> TeamCity chain fails certificate
// validation, so inject a local absolute file URI before project evaluation.
System.setProperty(
    "org.jetbrains.intellij.platform.productsReleasesAndroidStudioUrl",
    localAndroidStudioReleasesListUri,
)
val injectedIntellijPlatformProperties = mapOf(
    "org.jetbrains.intellij.platform.productsReleasesAndroidStudioUrl" to localAndroidStudioReleasesListUri,
    "org.jetbrains.intellij.platform.selfUpdateCheck" to "false",
)
runCatching {
    @Suppress("UNCHECKED_CAST")
    val projectProperties = gradle.startParameter.projectProperties as MutableMap<String, String>
    injectedIntellijPlatformProperties.forEach { (key, value) ->
        projectProperties.putIfAbsent(key, value)
    }
}
gradle.beforeProject {
    val project = this
    injectedIntellijPlatformProperties.forEach { (key, value) ->
        if (!project.extensions.extraProperties.has(key)) {
            project.extensions.extraProperties[key] = value
        }
    }
}

rootProject.name = "Spec Code"
