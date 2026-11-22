#!/bin/bash
# Manual v1.1 build for Oniel Music (Gradle is blocked by the sandbox TCP
# policy; see BUILD-NOTES.md). Steps:
#   1. aapt2 compile (app + library resources) + aapt2 link (--java R.java)
#   2. javac ALL generated R.java files (app + every library package)  <-- THE FIX
#   3. kotlinc 1.9.24 + Compose compiler 1.5.14
#   4. d8, package dex + native libs into the linked APK, zipalign, apksigner
set -euo pipefail

PROJ=~/workspace/oniel-music-apk
MB=$PROJ/manual-build
WORK=$MB/v1.1
DEPS=$MB/deps/files
SDK=~/Android/Sdk
BT=$SDK/build-tools/34.0.0
ANDROID_JAR=$SDK/platforms/android-34/android.jar
JAVA_HOME=~/jdk17
export PATH="$JAVA_HOME/bin:$PATH"   # d8/apksigner scripts need java on PATH
KOTLINC=~/kotlinc

log() { echo "[$(date +%H:%M:%S)] $*"; }

rm -rf "$WORK"
mkdir -p "$WORK"/{aar,compiled,gen,r-classes,kotlin-classes,dex}

# ---------- 1. unpack AARs ----------
log "unpacking AARs..."
> "$WORK/aar-list.txt"
for aar in "$DEPS"/*.aar; do
  name=$(basename "$aar" .aar)
  mkdir -p "$WORK/aar/$name"
  (cd "$WORK/aar/$name" && unzip -qo "$aar")
  echo "$name" >> "$WORK/aar-list.txt"
done
# plain jars (non-AAR) for the compile classpath / d8 input
# NOTE: the Compose compiler plugin jar is build-time only — it must NOT
# reach the app classpath or the dex (its classes reference Kotlin compiler
# internals that don't exist on a phone).
ls "$DEPS"/*.jar | grep -v 'androidx.compose.compiler__compiler__' > "$WORK/plain-jars.txt" || true

# ---------- 2. aapt2 compile ----------
log "aapt2 compile (app resources)..."
"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o "$WORK/compiled/app.zip"
i=0
while read -r name; do
  if [ -d "$WORK/aar/$name/res" ] && [ -n "$(find "$WORK/aar/$name/res" -type f | head -1)" ]; then
    "$BT/aapt2" compile --dir "$WORK/aar/$name/res" -o "$WORK/compiled/$name.zip"
    i=$((i+1))
  fi
done < "$WORK/aar-list.txt"
log "compiled $i library resource sets"

# library package names for --extra-packages (from AAR manifests)
EXTRA=""
while read -r name; do
  pkg=$(grep -o 'package="[^"]*"' "$WORK/aar/$name/AndroidManifest.xml" 2>/dev/null | head -1 | cut -d'"' -f2 || true)
  if [ -n "$pkg" ]; then EXTRA="$EXTRA --extra-packages $pkg"; fi
done < "$WORK/aar-list.txt"
log "library packages: $(echo $EXTRA | tr ' ' '\n' | grep -c extra-packages || true) extra-packages args"

# ---------- 3. aapt2 link ----------
log "aapt2 link..."
# The source manifest has no package= (AGP 8 takes it from `namespace`
# in app/build.gradle); aapt2 needs one, so link against a copy with it.
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android"|& package="com.oniel.music"|' \
  "$PROJ/app/src/main/AndroidManifest.xml" > "$WORK/AndroidManifest.xml"
LINK_ARGS=()
for z in "$WORK"/compiled/*.zip; do LINK_ARGS+=(-R "$z"); done
# shellcheck disable=SC2086
"$BT/aapt2" link -o "$WORK/linked.apk" \
  --manifest "$WORK/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  --java "$WORK/gen" \
  --version-code 2 --version-name 1.0.1 \
  --min-sdk-version 26 --target-sdk-version 34 \
  --auto-add-overlay \
  $EXTRA \
  "${LINK_ARGS[@]}"
log "aapt2 link OK; generated R.java files:"
find "$WORK/gen" -name 'R.java' | sort
echo "R.java count: $(find "$WORK/gen" -name 'R.java' | wc -l)"

# ---------- 4. javac ALL R.java (THE FIX) ----------
log "javac all R.java..."
find "$WORK/gen" -name 'R.java' > "$WORK/rjava-list.txt"
"$JAVA_HOME/bin/javac" -cp "$ANDROID_JAR" -d "$WORK/r-classes" @"$WORK/rjava-list.txt"
log "R classes compiled: $(find "$WORK/r-classes" -name '*.class' | wc -l)"

# ---------- 5. kotlinc ----------
log "kotlinc..."
# classpath: kotlin-stdlib 1.9.24 first, then every AAR classes.jar + plain jar, then R classes, then android.jar
CP="$MB/compiler/kotlin-stdlib-1.9.24.jar"
while read -r name; do
  [ -f "$WORK/aar/$name/classes.jar" ] && CP="$CP:$WORK/aar/$name/classes.jar"
done < "$WORK/aar-list.txt"
while read -r j; do CP="$CP:$j"; done < "$WORK/plain-jars.txt"
CP="$CP:$WORK/r-classes:$ANDROID_JAR"
echo "$CP" > "$WORK/classpath.txt"
COMPOSE_PLUGIN="$DEPS/androidx.compose.compiler__compiler__1.5.14.jar"
# Compiler JVM classpath: kotlin-compiler-EMBEDDABLE 1.9.24 (NOT ~/kotlinc's
# kotlin-compiler.jar — the standalone compiler uses unrelocated
# com.intellij.mock.MockProject while the Compose plugin was built against
# the embeddable's relocated org.jetbrains.kotlin.com.intellij.mock.MockProject;
# using the standalone jar makes the plugin fail with AbstractMethodError).
# annotations-13.0.jar must be on the compiler JVM's own classpath
# (codegen crash otherwise).
KC_CP="$MB/compiler/kotlin-compiler-embeddable-1.9.24.jar:$MB/compiler/kotlin-stdlib-1.9.24.jar:$MB/compiler/kotlin-reflect-1.9.24.jar:$MB/compiler/kotlin-script-runtime-1.9.24.jar:$MB/compiler/trove4j-1.0.20200330.jar:$MB/compiler/annotations-13.0.jar:${COMPOSE_PLUGIN}"
"$JAVA_HOME/bin/java" -Xmx3g --add-opens java.base/java.util=ALL-UNNAMED -cp "$KC_CP" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -no-stdlib \
  -Xplugin="$COMPOSE_PLUGIN" \
  -cp "$CP" \
  -d "$WORK/kotlin-classes" \
  $(find "$PROJ/app/src/main/java" -name '*.kt')
log "kotlinc OK: $(find "$WORK/kotlin-classes" -name '*.class' | wc -l) classes"

# ---------- 6. merge classes for d8 ----------
log "merging classes for d8..."
# priority order: R classes, app classes, kotlin-stdlib 1.9.24, then AAR classes.jars, then plain jars
MERGE_INPUTS=("$WORK/r-classes" "$WORK/kotlin-classes" "$MB/compiler/kotlin-stdlib-1.9.24.jar")
while read -r name; do
  [ -f "$WORK/aar/$name/classes.jar" ] && MERGE_INPUTS+=("$WORK/aar/$name/classes.jar")
done < "$WORK/aar-list.txt"
while read -r j; do MERGE_INPUTS+=("$j"); done < "$WORK/plain-jars.txt"
python3 "$MB/merge_jars.py" "$WORK/merged-classes" "$WORK/duplicates.log" "${MERGE_INPUTS[@]}"

# ---------- 7. d8 ----------
log "d8..."
(cd "$WORK/merged-classes" && "$JAVA_HOME/bin/jar" --create --file "$WORK/merged.jar" .)
"$BT/d8" --debug --min-api 26 --output "$WORK/dex" "$WORK/merged.jar"
log "dex files: $(ls "$WORK/dex")"

# ---------- 8. package ----------
log "packaging..."
cp "$WORK/linked.apk" "$WORK/unsigned.apk"
python3 - "$WORK/unsigned.apk" "$WORK/dex" "$WORK/aar" "$WORK/aar-list.txt" <<'PYEOF'
import sys, zipfile, os
apk, dexdir, aardir, aarlist = sys.argv[1:5]
names = [l.strip() for l in open(aarlist) if l.strip()]
z = zipfile.ZipFile(apk, 'a')
# dex files, DEFLATED (like the v1 APK — keeps the download ~12MB instead of ~33MB)
for fn in sorted(os.listdir(dexdir)):
    if fn.endswith('.dex'):
        z.write(os.path.join(dexdir, fn), fn, compress_type=zipfile.ZIP_DEFLATED)
# native libs from AAR jni/ dirs, stored
for name in names:
    jnidir = os.path.join(aardir, name, 'jni')
    if not os.path.isdir(jnidir):
        continue
    for root, _d, files in os.walk(jnidir):
        for f in files:
            if not f.endswith('.so'):
                continue
            p = os.path.join(root, f)
            arc = 'lib/' + os.path.relpath(p, jnidir)
            if arc not in z.namelist():
                z.write(p, arc, compress_type=zipfile.ZIP_STORED)
z.close()
print("packaged dex + native libs")
PYEOF

"$BT/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"

# debug keystore (Android debug key) if missing
if [ ! -f ~/.android/debug.keystore ]; then
  mkdir -p ~/.android
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore ~/.android/debug.keystore \
    -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --v1-signing-enabled true --ks ~/.android/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out "$WORK/OnielMusic-v1.1.apk" "$WORK/aligned.apk"
log "signed: $WORK/OnielMusic-v1.1.apk"
ls -l "$WORK/OnielMusic-v1.1.apk"
md5sum "$WORK/OnielMusic-v1.1.apk"
sha256sum "$WORK/OnielMusic-v1.1.apk"
