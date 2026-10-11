import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.zhiyu.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.zhiyu.app"
        minSdk = 26
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        versionCode = 9
        versionName = "0.2.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://local.zhiyu.invalid\"")
        buildConfigField("boolean", "USE_USB_BACKEND", "false")
        buildConfigField("boolean", "USE_LAN_PREVIEW", "false")
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs.excludes += "lib/x86/**" }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/portraitAssets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/semanticAssets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/voiceAssets").get().asFile)
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
val preparePortraitAssets = tasks.register("preparePortraitAssets") {
    val model = providers.gradleProperty("zhiyuPortraitModel").orElse("../artifacts/portrait-models/u2netp.onnx")
    val source = rootProject.file(model.get())
    val destination = layout.buildDirectory.dir("generated/portraitAssets")
    val seed = rootProject.file("../artifacts/native-media-seed")
    inputs.file(source)
    inputs.dir(seed)
    outputs.dir(destination)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { "%02x".format(it) }
        require(digest == "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8") { "Portrait model SHA256 mismatch" }
        destination.get().asFile.mkdirs()
        source.copyTo(destination.get().file("u2netp.onnx").asFile, overwrite = true)
        seed.copyRecursively(destination.get().dir("media_seed").asFile, overwrite = true)
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(preparePortraitAssets) }
val prepareSemanticAssets = tasks.register("prepareSemanticAssets") {
    val source = rootProject.file(providers.gradleProperty("zhiyuSemanticAssets")
        .orElse("../artifacts/semantic-models").get())
    val destination = layout.buildDirectory.dir("generated/semanticAssets/semantic")
    val checks = mapOf(
        "bge-small-zh-v1.5-int8.onnx" to "a20c8811f4c52f72f31783c615b6a6ea2eaac6b8b52eea5a107e004f4cc632f7",
        "vocab.txt" to "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c",
        "config.json" to "3853a7979202c348751b753e36f579c41d8da7d36af617d3d907e1fc9b441f2a",
        "tokenizer_config.json" to "e6f3b96db926a37d4039995fbf5ad17de158dfb8f6343d607e4dbaad18d75f5a",
        "manifest.json" to "60fc9061b2d9460a879707aede1fa3283241c3f425311bc462227c49c5d9d292",
    )
    inputs.files(checks.keys.map { source.resolve(it) })
    outputs.dir(destination)
    doLast {
        destination.get().asFile.mkdirs()
        checks.forEach { (name, expected) ->
            val file = source.resolve(name)
            val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            require(actual == expected) { "Semantic asset SHA256 mismatch: $name" }
            file.copyTo(destination.get().file(name).asFile, overwrite = true)
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareSemanticAssets) }
val verifyCompanionContract = tasks.register<Exec>("verifyCompanionContract") {
    workingDir(rootProject.file(".."))
    commandLine("python", "shared/generate_companion_contract.py", "--check")
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyCompanionContract) }

val prepareVoiceAssets = tasks.register("prepareVoiceAssets") {
    val source = rootProject.file("../artifacts/native-voice")
    val destination = layout.buildDirectory.dir("generated/voiceAssets/voice")
    val checks = mapOf(
        "vits-icefall-zh-aishell3/date.fst" to "eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718",
        "vits-icefall-zh-aishell3/lexicon.txt" to "ab2e61d357551e7b24ddd965d924aca784c20165ff58c150794e539c6b5e9e35",
        "vits-icefall-zh-aishell3/number.fst" to "743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd",
        "vits-icefall-zh-aishell3/speakers.txt" to "50a825fc9a89172eee04a4d68869d933d0eca0d847f3b6fcffff9514cc72442d",
        "vits-icefall-zh-aishell3/model.onnx" to "5511d651b7840c0a93a6bbfd4afd070a2c7f39ca1ec3ff2ecd73191519bbb852",
        "vits-icefall-zh-aishell3/new_heteronym.fst" to "ca14b2127e27baa571664e4bb791e143e7425f56a6bc29db08d74f97e6aa4e29",
        "vits-icefall-zh-aishell3/phone.fst" to "1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7",
        "vits-icefall-zh-aishell3/tokens.txt" to "50b45a7b7de1752fd3c7b4755661c285f1547f59186eca2281089a81307ad953",
        "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/tokens.txt" to "8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac",
        "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/encoder-epoch-99-avg-1.int8.onnx" to "1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1",
        "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/decoder-epoch-99-avg-1.onnx" to "5ee0f03a2768ff1d5c83ef3a493243c7935d316cd41280037b14783a3467cc78",
        "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/README.md" to "515751f07faad368f0863ae41773ff09635eba5920122d549abb8e45d5c88282",
        "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/joiner-epoch-99-avg-1.int8.onnx" to "a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169",
    )
    val aar = source.resolve("sherpa-onnx-static-link-onnxruntime-1.13.8.aar")
    inputs.files(checks.keys.map { source.resolve(it) } + aar)
    outputs.dir(destination)
    doLast {
        fun sha(file: java.io.File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        require(sha(aar) == "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471") { "Voice runtime SHA mismatch" }
        destination.get().file("sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/decoder-epoch-99-avg-1.int8.onnx").asFile.delete()
        checks.forEach { (name, expected) ->
            val original = source.resolve(name)
            require(sha(original) == expected) { "Voice model SHA mismatch: $name" }
            val target = destination.get().file(name).asFile
            target.parentFile.mkdirs(); original.copyTo(target, overwrite = true)
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareVoiceAssets) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation(files(rootProject.file("../artifacts/native-voice/sherpa-onnx-static-link-onnxruntime-1.13.8.aar")))
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
