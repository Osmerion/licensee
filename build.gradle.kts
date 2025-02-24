import app.cash.licensee.GenerateSpdxIdTask
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  alias(libs.plugins.android.lint)
  alias(libs.plugins.binary.compatibility.validator)
  alias(libs.plugins.dokka)
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.publish)
  alias(libs.plugins.spotless)
  id("dummy")
  id("java-gradle-plugin")
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(23)
  }
}

kotlin {
  compilerOptions {
    // Ensure compatibility with old Gradle versions. Keep in sync with LicenseePlugin.kt.
    apiVersion = KotlinVersion.KOTLIN_1_8

    jvmTarget = JvmTarget.JVM_11
    freeCompilerArgs.add("-Xjdk-release=11")
  }
}

gradlePlugin {
  plugins {
    register("licensee") {
      id = "app.cash.licensee"
      displayName = "Licensee"
      description = "Gradle plugin which validates the licenses of your dependency graph match what you expect"
      implementationClass = "app.cash.licensee.LicenseePlugin"
    }
  }
}

tasks {
  withType<JavaCompile>().configureEach {
    options.release = 11
  }

  test {
    dependsOn(":publishAllPublicationsToTestingRepository")

    systemProperty("licenseeVersion", project.property("VERSION_NAME") as String)
    systemProperty("generatedSpdxFile", file("build/generated/spdx/app/cash/licensee/licensesSpdx.kt").path)
    systemProperty("line.separator", "\n")

    testLogging {
      if (System.getenv("CI") == "true") {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED, TestLogEvent.PASSED)
      }

      exceptionFormat = TestExceptionFormat.FULL
    }

    // Required to test configuration cache in tests when using withDebug()
    // https://github.com/gradle/gradle/issues/22765#issuecomment-1339427241
    jvmArgs(
      "--add-opens",
      "java.base/java.util=ALL-UNNAMED",
      "--add-opens",
      "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
      "--add-opens",
      "java.base/java.lang.invoke=ALL-UNNAMED",
      "--add-opens",
      "java.base/java.net=ALL-UNNAMED",
    )
  }

  validatePlugins {
    enableStricterValidation = true
  }


  val fixtures = file("src/test/fixtures")
  val minimalJarBase64 = "UEsFBgAAAAAAAAAAAAAAAAAAAAAAAA=="

  register("writeFixtureJars") {
    doFirst {
      fixtures.listFiles()?.forEach { file ->
          if (file.toString().contains("/repo/") && file.name.endsWith(".jar")) {
            @OptIn(ExperimentalEncodingApi::class)
            file.writeBytes(Base64.decode(minimalJarBase64))
          }
      }
    }
  }

  val checkFixtureJars = register("checkFixtureJars") {
    doFirst {
      fixtures.listFiles()?.forEach { file ->
        if (file.toString().contains("/repo/") && file.name.endsWith(".jar")) {
          @OptIn(ExperimentalEncodingApi::class)
          val fileBase64 = Base64.encode(file.readBytes())
          if (fileBase64 != minimalJarBase64) {
            throw RuntimeException(
              "Expected '$minimalJarBase64' but was '$fileBase64'\n\n" +
                "Invoke 'writeFixtureJars' task to fix."
            )
          }
        }
      }
    }
  }

  check {
    dependsOn(checkFixtureJars)
  }

  val jsonFolder = layout.projectDirectory.dir("src/main/resources/app/cash/licensee")
  val jsonFile = jsonFolder.file("licenses.json")

  register<Copy>("downloadLicensesJson") {
    group = "generatespdx"

    from(resources.text.fromUri("https://spdx.org/licenses/licenses.json").asFile()) {
      rename {
        jsonFile.asFile.name
      }
    }

    into(jsonFolder)

    // TODO - Figure out how to port this
//    doLast {
//      val destFile = jsonFile.asFile
//      val json = JsonSlurper().parse(destFile)
//      json.licenses = json.licenses.sort { a, b -> a.licenseId <=> b.licenseId }
//      destFile.text = JsonOutput.prettyPrint(JsonOutput.toJson(json))
//    }
  }

  val generateSpdx by registering(GenerateSpdxIdTask::class) {
    inputJson = jsonFile
  }

  sourceSets.main.configure {
    kotlin.srcDir(generateSpdx)
  }
}

publishing {
  repositories {
    maven {
      name = "testing"
      setUrl("${rootProject.projectDir}/build/localMaven")
    }
  }
}

spotless {
  kotlin {
    target("**/*.kt")
    targetExclude("**/src/test/test-build-logic/build/**", "**/generated-sources/**")
    ktlint(libs.ktlint.get().version).editorConfigOverride(mapOf(
      "ktlint_standard_filename" to "disabled",
      // Making something an expression body should be a choice around readability.
      "ktlint_standard_function-expression-body" to "disabled"
    ))
    licenseHeaderFile(rootProject.file("gradle/license-header.txt"))
  }
}

dependencies {
  compileOnly(libs.androidGradleApi)
  compileOnly(libs.kotlinGradlePlugin)
  implementation(libs.kotlinx.serialization)
  implementation(libs.maven.modelBuilder)

  testImplementation(libs.junit)
  testImplementation(libs.assertk)
  testImplementation(libs.testParameterInjector)
  testImplementation(gradleTestKit())

  lintChecks(libs.androidx.gradlePluginLints)
}
