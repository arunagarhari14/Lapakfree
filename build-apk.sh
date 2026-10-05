#!/bin/bash
# Lapak Free manual APK build v1.7+ (aapt2 -> javac -> d8 -> zipalign -> apksigner).
# Zero third-party libraries — Google login (OAuth2+PKCE) aur Gemini AI seedhe HTTPS se.
set -e
# CI (GitHub Actions) me JAVA_HOME / SDK env se aate hain; local par default paths.
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
BT=$SDK/build-tools/34.0.0
# version CI se (env) ya default
VC="${VERSION_CODE:-16}"
VN="${VERSION_NAME:-3.0.2}"
ROOT=~/workspace/lapakfree
SRC=$ROOT/app/src/main
BUILD=/tmp/lfbuild
OUT=$ROOT/apk

# OAuth client ID (app ke dedicated Google account se) values me daalo
python3 $ROOT/tools/gen_oauth_values.py
# In-app updater ka version.json URL values me daalo (v2.2)
python3 $ROOT/tools/gen_update_values.py

rm -rf $BUILD; mkdir -p $BUILD/gen $BUILD/classes $BUILD/dex $OUT

echo "== aapt2 compile+link =="
$BT/aapt2 compile --dir $SRC/res -o $BUILD/compiled.zip
$BT/aapt2 link -o $BUILD/app-unsigned.apk -I $SDK/platforms/android-34/android.jar \
  --manifest $SRC/AndroidManifest.xml --java $BUILD/gen \
  -A $SRC/assets \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VC" --version-name "$VN" \
  $BUILD/compiled.zip

echo "== javac =="
find $SRC/java $BUILD/gen -name "*.java" > $BUILD/sources.txt
javac -encoding UTF-8 -source 17 -target 17 -cp $SDK/platforms/android-34/android.jar \
  -d $BUILD/classes @$BUILD/sources.txt

echo "== d8 =="
$BT/d8 --lib $SDK/platforms/android-34/android.jar --min-api 26 --output $BUILD/dex \
  $(find $BUILD/classes -name "*.class")

echo "== package/align/sign =="
cp $BUILD/app-unsigned.apk $BUILD/app.apk
(cd $BUILD/dex && zip -q $BUILD/app.apk classes.dex)
$BT/zipalign -f -p 4 $BUILD/app.apk $BUILD/app-aligned.apk
$BT/apksigner sign --ks $ROOT/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out $OUT/lapakfree-debug.apk $BUILD/app-aligned.apk
$BT/apksigner verify --print-certs $OUT/lapakfree-debug.apk | head -2
ls -la $OUT/lapakfree-debug.apk
echo BUILD_OK
