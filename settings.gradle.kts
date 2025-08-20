pluginManagement {
  plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
  }

  repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
  }

  includeBuild("build-logic")
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention")
}

rootProject.name = "licensee"

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS

  repositories {
    mavenCentral()
    google()
  }
}

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
