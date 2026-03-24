pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
        mavenCentral()
    }
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
gradle.beforeProject {
    val project = this
    if (!project.extensions.extraProperties.has("org.jetbrains.intellij.platform.productsReleasesAndroidStudioUrl")) {
        project.extensions.extraProperties["org.jetbrains.intellij.platform.productsReleasesAndroidStudioUrl"] =
            localAndroidStudioReleasesListUri
    }
}

rootProject.name = "Spec Code"
