#!/usr/bin/env python3
"""Exports the shareable Brookstream app as a clean, self-contained project.

    cd android && ./gradlew assembleRelease -Pstandalone    # makes the lists it bundles
    python3 tools/export_standalone.py OUT_DIR              # OUT_DIR/Brookstream (source), then build it

What changes in the copy:
- The lists the standalone build made (shows, News feeds, books with the Bible,
  Bible texts) go straight into app/src/main/assets, with their notes removed;
  android/standalone.json (what differs from the home build) isn't copied.
- No .env, Home Assistant address, token or home location; the build reads
  nothing outside the project. Local radio is around standalone.json's place.
- The package is com.brookstream.app, and names tied to the home setup are neutral.
- Then every file is checked for personal details; the export fails if any are left.
"""
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID = os.path.join(ROOT, 'android')
ASSETS = os.path.join(ANDROID, 'app', 'build', 'podcast-assets')
OLD_PKG, NEW_PKG = 'com.rochehome.podcasts', 'com.brookstream.app'

# Text swapped in the copied source (regex, replacement).
SWAPS = [
    (re.escape(OLD_PKG), NEW_PKG),
    (r'RochePodcasts/1\.0 \(Android; personal\)', 'Brookstream/1.0 (Android)'),
    (r'RochePodcasts', 'Brookstream'),
    (r'sensor\.roche_podcasts', 'sensor.brookstream'),
    (r'roche-home', 'brookstream'),
    (r'Roche Home', 'the TV app'),
    (r'"Conejo Valley", ', ''),
    (r'"Santa Barbara", ', ''),
    (r'RocheHome-bible', 'Brookstream-bible'),
]
# Nothing like these may be left in the code, config or docs...
PERSONAL = re.compile(r'roche|broche|upsellit|conejo|thousand oaks|santa barbara|ventura|moorpark|simi valley|agoura|'
                      r'192\.168\.|\.duckdns|nabu\.casa|:8123|/Users/', re.I)
# ...or the show and News lists (the books and Bible texts are public-domain literature, where these words occur).
LOCAL = re.compile(r'conejo|thousand oaks|santa barbara|ventura|moorpark|simi valley|agoura|acorn|noozhawk|edhat|kclu', re.I)
CHECKED_LISTS = ('sources.json', 'news.json')

GRADLE = '''import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key: keys/keystore.properties and the .jks beside it. Updates must be signed with it to install over the app.
val keyProps = Properties().apply { rootProject.file("keys/keystore.properties").takeIf { it.exists() }?.reader()?.use { load(it) } }

fun quoted(s: String) = "\\"" + s.replace("\\\\", "\\\\\\\\").replace("\\"", "\\\\\\"") + "\\""

android {
    namespace = "%(pkg)s"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "%(pkg)s"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        // No Home Assistant: every feed is read on the phone, and progress stays there.
        buildConfigField("String", "HA_URL", quoted(""))
        buildConfigField("String", "HA_TOKEN", quoted(""))
        // The Radio tab's Local stations are around here.
        buildConfigField("String", "LATITUDE", quoted("%(lat)s"))
        buildConfigField("String", "LONGITUDE", quoted("%(lon)s"))
        buildConfigField("String", "PLACE", quoted("%(place)s"))
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file("keys/" + keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        // Shrunk to ~30 MB, and signed with the release key (without it, the debug key, which can't update an installed copy).
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

'''

README = '''# Brookstream

A podcast, audiobook, radio, news and Bible app for Android (10 and up).

- **Install:** copy `Brookstream.apk` to the phone and open it (allow installing
  from your file manager when asked).
- **Build:** open this folder in Android Studio, or run `./gradlew assembleRelease`
  with Java 17+; the APK is in `app/build/outputs/apk/release/`.
- **Updates:** a new APK installs over the old one, keeping your progress, only if
  it's signed with the key in `keys/` (the build does that). Keep a copy of that
  folder somewhere safe: without it, an update means uninstalling first. Don't
  publish it.

Podcasts are read straight from each show's feed; listening progress stays on the
phone. Lists it starts with are in `app/src/main/assets` (`sources.json` shows,
`news.json` feeds, `audiobooks.json` books). The Radio tab's Local stations are
around %(place)s (`app/build.gradle.kts`).

## Sources

- Audiobooks, including the King James and World English Bible readings:
  [LibriVox](https://librivox.org) volunteers, public domain.
- Bible texts (`assets/bible`), all public domain: Berean Standard Bible, King James
  Version, World English Bible (ebible.org), American Standard Version, Young's
  Literal Translation, the Westminster Leningrad Codex (Hebrew) and the Textus
  Receptus (Greek), via [scrollmapper/bible_databases](https://github.com/scrollmapper/bible_databases).
  `tools/bible/build.py` rebuilds them. NIV, ESV, NLT, NASB, CSB and NKJV are
  copyrighted, so the reader links to them on BibleGateway instead.
- Radio stations: [Radio Browser](https://www.radio-browser.info) and SomaFM.
'''


def clean_json(path):
    """Drop the "_note" keys (they describe how the lists were made)."""
    with open(path) as f:
        data = json.load(f)
    if isinstance(data, dict):
        data.pop('_note', None)
    with open(path, 'w') as f:
        json.dump(data, f, ensure_ascii=False, separators=(',', ':'))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    if not os.path.isdir(os.path.join(ASSETS, 'bible')):
        sys.exit('Build the standalone app first: cd android && ./gradlew assembleRelease -Pstandalone')
    cfg = json.load(open(os.path.join(ANDROID, 'standalone.json')))
    out = os.path.join(os.path.abspath(sys.argv[1]), 'Brookstream')
    shutil.rmtree(out, ignore_errors=True)

    # The project, minus builds, local paths and anything git ignores.
    for name in ['gradle', 'gradlew', 'gradlew.bat', 'build.gradle.kts', 'gradle.properties', '.gitignore']:
        src = os.path.join(ANDROID, name)
        (shutil.copytree if os.path.isdir(src) else shutil.copy2)(src, os.path.join(out, name))
    shutil.copytree(os.path.join(ANDROID, 'app', 'src'), os.path.join(out, 'app', 'src'))
    with open(os.path.join(ANDROID, 'settings.gradle.kts')) as f:
        settings = f.read()
    with open(os.path.join(out, 'settings.gradle.kts'), 'w') as f:
        f.write(settings)
    with open(os.path.join(out, 'app', 'build.gradle.kts'), 'w') as f:
        f.write(GRADLE % {'pkg': NEW_PKG, 'lat': cfg['latitude'], 'lon': cfg['longitude'], 'place': cfg['place']})
        # The libraries, as the home build has them.
        with open(os.path.join(ANDROID, 'app', 'build.gradle.kts')) as g:
            home = g.read()
        f.write(home[home.index('\ndependencies {') + 1:])
    with open(os.path.join(out, 'README.md'), 'w') as f:
        f.write(README % {'place': cfg['place']})

    # The lists, as the standalone build made them.
    assets = os.path.join(out, 'app', 'src', 'main', 'assets')
    shutil.copytree(ASSETS, assets)
    for name in ['sources.json', 'news.json', 'audiobooks.json']:
        clean_json(os.path.join(assets, name))

    # The release key, so whoever has this copy can make updates that install over the app.
    shutil.copytree(os.path.join(ANDROID, 'keys'), os.path.join(out, 'keys'))
    with open(os.path.join(out, '.gitignore'), 'a') as f:
        f.write('\n# The release key: keep a copy safe, never publish it.\nkeys/\n')

    # The Bible data script.
    os.makedirs(os.path.join(out, 'tools', 'bible'))
    with open(os.path.join(ROOT, 'tools', 'bible', 'build.py')) as f:
        script = f.read().replace("OUT = os.path.join(HERE, 'data')",
                                  "OUT = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'assets', 'bible')")
    with open(os.path.join(out, 'tools', 'bible', 'build.py'), 'w') as f:
        f.write(script)

    # The new package: its folder, then the text.
    java = os.path.join(out, 'app', 'src', 'main', 'java')
    shutil.move(os.path.join(java, *OLD_PKG.split('.')), os.path.join(java, 'pkg-tmp'))
    shutil.rmtree(os.path.join(java, OLD_PKG.split('.')[0]))
    os.makedirs(os.path.join(java, *NEW_PKG.split('.')[:-1]))
    shutil.move(os.path.join(java, 'pkg-tmp'), os.path.join(java, *NEW_PKG.split('.')))
    for d, _, files in os.walk(out):
        for name in files:
            if not name.endswith(('.kt', '.kts', '.xml', '.py', '.md', '.properties', '.pro')):
                continue
            p = os.path.join(d, name)
            with open(p) as f:
                text = f.read()
            new = text
            for a, b in SWAPS:
                new = re.sub(a, b, new)
            if new != text:
                with open(p, 'w') as f:
                    f.write(new)
    with open(os.path.join(out, 'settings.gradle.kts')) as f:
        assert 'Brookstream' in f.read()

    # Nothing personal left.
    left = []
    for d, _, files in os.walk(out):
        for name in files:
            p = os.path.join(d, name)
            rel = os.path.relpath(p, out)
            if rel.startswith(os.path.join('app', 'src', 'main', 'assets')):
                if name not in CHECKED_LISTS:
                    continue
                pattern = LOCAL
            elif name.endswith(('.kt', '.kts', '.xml', '.py', '.md', '.properties', '.pro', '.bat', 'gradlew', '.gitignore')):
                pattern = PERSONAL
            else:
                continue
            with open(p, encoding='utf-8', errors='ignore') as f:
                for n, line in enumerate(f, 1):
                    for m in pattern.finditer(line):
                        left.append(f'{rel}:{n}: {m.group(0)!r} in ...{line[max(0, m.start() - 50):m.end() + 50].strip()}...')
    if left:
        sys.exit('Personal details left in the export:\n' + '\n'.join(left))
    print(out)


if __name__ == '__main__':
    main()
