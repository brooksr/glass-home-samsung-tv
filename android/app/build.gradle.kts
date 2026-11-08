plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Home Assistant's address and token come from the repo's .env (git-ignored),
// as for the TV app. HA serves the podcast lists and keeps the shared progress.
// `-Pstandalone` leaves all of that out (no Home Assistant, token or home location in the APK),
// for a copy to share: it reads every feed directly and keeps progress on the phone.
val standalone = project.hasProperty("standalone")
val env: Map<String, String> = rootProject.file("../.env").takeIf { it.exists() && !standalone }?.readLines().orEmpty()
    .mapNotNull { Regex("""^\s*(?:export\s+)?([A-Z0-9_]+)\s*=\s*(.*?)\s*$""").find(it) }
    .associate { m ->
        m.groupValues[1] to m.groupValues[2].replace(Regex("""\s+#.*$"""), "").trim('"', '\'')
    }
fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// The show list bundled in the app (used until Home Assistant has been reached once), the News feeds and the audiobooks.
val podcastAssets = layout.buildDirectory.dir("podcast-assets")
// Sync, not Copy: what one kind of build adds (the Bible) mustn't linger in the other.
val copySources by tasks.registering(Sync::class) {
    from(rootProject.file("../tools/podcasts/sources.json"))
    from(rootProject.file("../tools/podcasts/news.json"))
    from(rootProject.file("../tools/audiobooks/audiobooks.json"))
    // The Watch tab's titles as of the last tools/watch/build.py run (Home Assistant serves newer ones).
    if (!standalone) from(rootProject.file("../tools/watch/out")) { include("watch.json") }
    // Read-along text (tools/audiobooks/texts.py writes it; books without it just have none).
    from(rootProject.file("../tools/audiobooks/texts")) { into("texts") }
    into(podcastAssets)
    // The shareable build's own lists (android/standalone.json): fewer shows and feeds, extra books first.
    inputs.property("standalone", standalone)
    if (standalone) {
        // The Bible reader's texts (tools/bible/build.py).
        from(rootProject.file("../tools/bible/data")) { into("bible") }
        val cfgFile = rootProject.file("standalone.json")
        val extraBooks = rootProject.file("../tools/audiobooks/audiobooks-standalone.json")
        inputs.files(cfgFile, extraBooks)
        doLast {
            val dir = podcastAssets.get().asFile
            @Suppress("UNCHECKED_CAST")
            fun read(f: File) = groovy.json.JsonSlurper().parse(f, "UTF-8") as MutableMap<String, Any?>
            fun write(f: File, m: Map<String, Any?>) = f.writeText(groovy.json.JsonOutput.toJson(m))
            val cfg = read(cfgFile)
            val dropShows = (cfg["dropShows"] as Map<*, *>).keys
            val dropNews = (cfg["dropNews"] as List<*>).toSet()
            val dropNewsCategories = (cfg["dropNewsCategories"] as? List<*>).orEmpty().toSet()
            val addShows = cfg["addShows"] as? List<*> ?: emptyList<Any>()
            File(dir, "sources.json").let { f ->
                val m = read(f)
                m["shows"] = ((m["shows"] as List<*>).filter { (it as Map<*, *>)["id"] !in dropShows } + addShows)
                    .sortedBy { ((it as Map<*, *>)["title"] as String).lowercase().replace(Regex("^(the|a|an) "), "") }
                write(f, m)
            }
            File(dir, "news.json").let { f -> val m = read(f); m["feeds"] = (m["feeds"] as List<*>).filter { (it as Map<*, *>)["name"] !in dropNews && (it as Map<*, *>)["category"] !in dropNewsCategories }; write(f, m) }
            File(dir, "audiobooks.json").let { f -> val m = read(f); m["books"] = (read(extraBooks)["books"] as List<*>) + (m["books"] as List<*>); write(f, m) }
        }
    }
}
val standaloneCfg: Map<*, *> = if (standalone) groovy.json.JsonSlurper().parse(rootProject.file("standalone.json")) as Map<*, *> else emptyMap<String, Any>()

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
        buildConfigField("String", "LATITUDE", quoted(standaloneCfg["latitude"]?.toString() ?: env["LATITUDE"].orEmpty()))
        buildConfigField("String", "LONGITUDE", quoted(standaloneCfg["longitude"]?.toString() ?: env["LONGITUDE"].orEmpty()))
        // Where "Local" radio is around (the shareable build names its place; at home it's just home).
        buildConfigField("String", "PLACE", quoted(standaloneCfg["place"]?.toString() ?: "home"))
    }
    buildTypes {
        // A shrunk build for sharing (the debug one carries every library whole, ~140 MB).
        // Signed with the debug key for now, so it installs over the debug build.
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
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
