plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.videoconverter.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.videoconverter.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")
    }
    sourceSets.getByName("test") {
        resources.srcDir(layout.buildDirectory.dir("generated/pdfboxTestResources"))
    }
}

val unpackPdfBoxTestResources by tasks.registering(Copy::class) {
    val pdfBoxAar = configurations.detachedConfiguration(
        dependencies.create("com.tom-roush:pdfbox-android:2.0.27.0"),
    ).apply {
        isTransitive = false
        isCanBeResolved = true
    }
    from({ zipTree(pdfBoxAar.singleFile) }) {
        include("assets/com/tom_roush/pdfbox/resources/**")
        eachFile {
            relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/pdfboxTestResources"))
}

tasks.configureEach {
    if (name.startsWith("process") && name.endsWith("UnitTestJavaRes")) {
        dependsOn(unpackPdfBoxTestResources)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
