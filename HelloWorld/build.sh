#!/bin/bash
set -e

BUILD_TOOLS=/home/martin/Android/Sdk/build-tools/36.1.0
PLATFORM=/home/martin/Android/Sdk/platforms/android-36/android.jar

mkdir -p compiled_res gen obj dex

$BUILD_TOOLS/aapt2 compile res/layout/activity_main.xml -o compiled_res/
$BUILD_TOOLS/aapt2 compile res/values/strings.xml -o compiled_res/
$BUILD_TOOLS/aapt2 link -o base_unsigned.apk -I $PLATFORM --manifest AndroidManifest.xml --java gen compiled_res/*.flat

javac -source 8 -target 8 -classpath $PLATFORM -d obj \
  gen/com/example/helloworld/R.java \
  src/com/example/helloworld/MainActivity.java

$BUILD_TOOLS/d8 --output dex obj/com/example/helloworld/*.class

cp base_unsigned.apk unsigned.apk
zip -j unsigned.apk dex/classes.dex

$BUILD_TOOLS/zipalign -f 4 unsigned.apk aligned.apk

$BUILD_TOOLS/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android \
  --ks-key-alias androiddebugkey \
  --key-pass pass:android \
  --out helloworld.apk aligned.apk

echo "Build OK"
