plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Home Assistant's address and token come from the repo's .env (git-ignored),
// as for the TV app. HA serves the podcast lists and keeps the shared progress.
val env: Map<String, String> = rootProject.file("../.env").takeIf { it.exists() }?.readLines().orEmpty()
    .mapNotNull { Regex("""^\s*(?:export\s+)?([A-Z0-9_]+)\s*=\s*(.*?)\s*$""").find(it) }
    .associate { m ->
        m.groupValues[1] to m.groupValues[2].replace(Regex("""\s+#.*$"""), "").trim('"', '\'')
    }
fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// The show list bundled in the app (used until Home Assistant has been reached once), the News feeds and the audiobooks.
val podcastAssets = layout.buildDirectory.dir("podcast-assets")
val copySources by tasks.registering(Copy::class) {
    from(rootProject.file("../tools/podcasts/sources.json"))
    from(rootProject.file("../tools/podcasts/news.json"))
    from(rootProject.file("../tools/audiobooks/audiobooks.json"))
    // Read-along text (tools/audiobooks/texts.py writes it; books without it just have none).
    from(rootProject.file("../tools/audiobooks/texts")) { into("texts") }
    into(podcastAssets)
}

android {
    namespace = "com.rochehome.podcasts"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "com.rochehome.podcasts"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "HA_URL", quoted(env["HA_URL"].orEmpty().trimEnd('/')))
        buildConfigField("String", "HA_TOKEN", quoted(env["HA_TOKEN"].orEmpty()))
        // Home, for the Radio tab's Local stations (the same coordinates as the TV's weather).
        buildConfigField("String", "LATITUDE", quoted(env["LATITUDE"].orEmpty()))
        buildConfigField("String", "LONGITUDE", quoted(env["LONGITUDE"].orEmpty()))
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") { assets.srcDir(podcastAssets.get().asFile) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.named("preBuild") { dependsOn(copySources) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")   // radio stations that stream HLS
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
}
