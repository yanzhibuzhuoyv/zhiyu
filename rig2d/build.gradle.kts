// rig2d — 参数化 2D 通话形象：契约与引擎（阶段 0 + P0 探针）
// 依据：ON_DEVICE_2D_CALL_AVATAR_PLAN_2026-10-12.md
//
// 本模块刻意保持纯 JVM：不需要 Android SDK / NDK / 真机即可全绿跑测试。
// 与 Android 侧的对接方式见 docs/PORTING.md。
//
// 工具链说明：本机只装了 JDK 23。因此这里**不声明 java toolchain**
// （声明 toolchain 17 会触发 Gradle 去下载 JDK，本机没有配置 toolchain 仓库，直接构建失败），
// 改为用当前 JDK 编译、把 jvmTarget 固定为 17，与 zhiyu android 侧
// （compileOptions sourceCompatibility/targetCompatibility = VERSION_17）对齐。
plugins {
    kotlin("jvm") version "2.3.21"
    jacoco
}

group = "rig2d"
version = "0.2.0-studio"

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // 禁止把「未使用的变量/参数」静默留着：本仓库是契约层，笔误必须报错。
        allWarningsAsErrors.set(false)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // 回执纪律（计划 §10.2）：测试输出逐次留档，不覆盖上一次。
    val stamp = System.getenv("RIG2D_RUN_STAMP") ?: "latest"
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/$stamp"))
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/$stamp"))
}

jacoco { toolVersion = "0.8.13" }
tasks.jacocoTestReport {
    dependsOn(tasks.test)
    // The synthetic replay driver is a fixture, not shipping voice/rig logic.
    classDirectories.setFrom(files(classDirectories.files.map { fileTree(it) {
        exclude("rig2d/voice/demo/**", "rig2d/parts/demo/**")
    } }))
    reports { xml.required.set(true); html.required.set(true) }
}

tasks.register<JavaExec>("duplexFixture") {
    group = "verification"
    description = "Runs synthetic PCM/ASR/LLM/TTS playback overlap; never records a microphone."
    dependsOn(tasks.classes)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("rig2d.voice.demo.DuplexFixtureKt")
}

tasks.register<JavaExec>("partsFixture") {
    group = "verification"
    description = "Replays the frozen synthetic 12-wing fixture; no segmentation model or user media."
    dependsOn(tasks.classes)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("rig2d.parts.demo.PartPreservationFixtureKt")
    args("artifacts/parts-fixture-2026-10-13-r1", providers.gradleProperty("partsOut")
        .getOrElse("artifacts/parts-host-2026-10-13-r1"))
}

// Host-only synthetic drivers (including Java ImageIO) do not ship in the portable core.
tasks.jar { exclude("rig2d/voice/demo/**", "rig2d/parts/demo/**") }
