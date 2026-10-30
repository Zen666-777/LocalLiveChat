plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
providers.gradleProperty("melLocalBuildDirectory").orNull?.let {
    layout.buildDirectory.set(file(it))
}
android {
    namespace = "jp.mel.chat"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "jp.mel.chat"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.1-memory"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    buildFeatures { compose = true }
    val fixedKeystore = providers.environmentVariable("MEL_SIGNING_KEYSTORE_FILE")
    if (fixedKeystore.isPresent) {
        signingConfigs.getByName("debug") {
            storeFile = file(fixedKeystore.get())
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir("schemas")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
tasks.withType<Test>().configureEach {
    val evaluationDirectory = layout.buildDirectory.dir("evaluation/$name")
    systemProperty("melEvaluationDirectory", evaluationDirectory.get().asFile.absolutePath)
    outputs.dir(evaluationDirectory).withPropertyName("melQualityEvaluation")
}
// OneDrive can turn an old binary-results directory into a read-only reparse
// point. Local builds use a fresh path; CI keeps Gradle's standard output.
val localTestRun = providers.gradleProperty("melLocalTestRun")
if (localTestRun.isPresent) {
    tasks.withType<Test>().configureEach {
        binaryResultsDirectory.set(layout.buildDirectory.dir("test-binary-local/${localTestRun.get()}/$name"))
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
