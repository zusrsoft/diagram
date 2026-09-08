plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(projects.diagramCore)
            implementation(projects.diagramLayout)
            implementation(projects.diagramParser)
            implementation(projects.diagramRender)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.register<JavaExec>("runBench") {
    group = "verification"
    description = "Runs the streaming append/finish micro-benchmark and prints percentiles."
    val jvmCompilation = kotlin.jvm().compilations.getByName("main")
    classpath = jvmCompilation.output.allOutputs + jvmCompilation.runtimeDependencyFiles!!
    mainClass.set("com.hrm.diagram.bench.SessionBenchMainKt")
}
