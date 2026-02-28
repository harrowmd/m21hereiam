#!/bin/bash
set -e

BUILD_TOOLS=/home/martin/Android/Sdk/build-tools/36.1.0
PLATFORM=/home/martin/Android/Sdk/platforms/android-36/android.jar

mkdir -p compiled_res gen obj dex

# Generate build info resource
BUILD_DATE=$(date '+%Y-%m-%d %H:%M')
BUILD_CODE=$(date '+%Y%m%d%H%M')
cat > res/values/build_info.xml << BUILDEOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="build_date">$BUILD_DATE</string>
    <string name="build_code">$BUILD_CODE</string>
</resources>
BUILDEOF

$BUILD_TOOLS/aapt2 compile res/layout/activity_main.xml -o compiled_res/
$BUILD_TOOLS/aapt2 compile res/values/strings.xml -o compiled_res/
$BUILD_TOOLS/aapt2 compile res/values/build_info.xml -o compiled_res/
$BUILD_TOOLS/aapt2 compile res/mipmap-mdpi/ic_launcher.png -o compiled_res/
$BUILD_TOOLS/aapt2 compile res/mipmap-hdpi/ic_launcher.png -o compiled_res/
$BUILD_TOOLS/aapt2 compile res/mipmap-xhdpi/ic_launcher.png -o compiled_res/
$BUILD_TOOLS/aapt2 compile res/mipmap-xxhdpi/ic_launcher.png -o compiled_res/
$BUILD_TOOLS/aapt2 compile res/mipmap-xxxhdpi/ic_launcher.png -o compiled_res/
$BUILD_TOOLS/aapt2 link -o base_unsigned.apk -I $PLATFORM --manifest AndroidManifest.xml --java gen compiled_res/*.flat

javac -source 8 -target 8 -classpath $PLATFORM -d obj \
  gen/com/example/m21hereiam/R.java \
  src/com/example/m21hereiam/MapView.java \
  src/com/example/m21hereiam/LocationService.java \
  src/com/example/m21hereiam/BootReceiver.java \
  src/com/example/m21hereiam/MainActivity.java

$BUILD_TOOLS/d8 --output dex obj/com/example/m21hereiam/*.class obj/com/example/m21hereiam/**/*.class 2>/dev/null || \
$BUILD_TOOLS/d8 --output dex obj/com/example/m21hereiam/*.class

cp base_unsigned.apk unsigned.apk
zip -j unsigned.apk dex/classes.dex

$BUILD_TOOLS/zipalign -f 4 unsigned.apk aligned.apk

$BUILD_TOOLS/apksigner sign \
  --ks ~/.android/debug.keystore \
  --ks-pass pass:android \
  --ks-key-alias androiddebugkey \
  --key-pass pass:android \
  --out m21hereiamnow.apk aligned.apk

echo "Build OK"
