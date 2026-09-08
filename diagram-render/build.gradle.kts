import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.hrm.diagram.render"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}

        // 发布消费方 R8 / ProGuard 规则，随 AAR 一起分发给下游使用方
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file(file("consumer-rules.pro"))
        }
    }

    jvm { }
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "DiagramRender"
            isStatic = true
        }
    }
    js { browser(); nodejs() }
    @OptIn(ExperimentalWasmDsl::class) wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(projects.diagramCore)
            api(projects.diagramLayout)
            api(projects.diagramParser)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral(true)

    if (!project.hasProperty("signing.skip")) {
        signAllPublications()
    }

    coordinates("io.github.zusrsoft", "diagram-render", rootProject.property("VERSION").toString())

    pom {
        name.set("Diagram Render")
        description.set("Compose Multiplatform rendering facade and streaming session APIs for Diagram.")
        inceptionYear.set("2026")
        url.set("https://github.com/zusrsoft/diagram")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("zusrsoft")
                name.set("zusrsoft")
                url.set("https://github.com/zusrsoft/")
            }
        }
        scm {
            url.set("https://github.com/zusrsoft/diagram")
            connection.set("scm:git:git://github.com/zusrsoft/diagram.git")
            developerConnection.set("scm:git:ssh://git@github.com/zusrsoft/diagram.git")
        }
    }
}
