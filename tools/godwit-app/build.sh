#!/bin/sh
# Build Godwit.apk without Gradle (same toolchain as rom-tuner/build.sh). Run from this directory. The keystore is made on first run and is not committed.
set -e
SDK=$HOME/Library/Android/sdk; BT=$SDK/build-tools/35.0.0; PL=$SDK/platforms/android-34/android.jar
rm -rf gen out; mkdir -p gen out/classes
$BT/aapt2 compile --dir res -o out/res.zip
$BT/aapt2 link -o out/base.apk -I $PL --manifest AndroidManifest.xml --java gen --min-sdk-version 29 --target-sdk-version 34 out/res.zip
javac -source 8 -target 8 -classpath $PL -d out/classes $(find src gen -name "*.java") > out/javac.log 2>&1 || { grep -v "^Note\|warning" out/javac.log; exit 1; }
$BT/d8 --lib $PL --min-api 29 --output out $(find out/classes -name '*.class')
cp out/base.apk out/unsigned.apk; (cd out && zip -q unsigned.apk classes.dex)
$BT/zipalign -f 4 out/unsigned.apk out/aligned.apk
[ -f godwit.keystore ] || keytool -genkeypair -keystore godwit.keystore -storepass godwit123 -alias godwit -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Godwit viewer"
$BT/apksigner sign --ks godwit.keystore --ks-pass pass:godwit123 --out Godwit.apk out/aligned.apk
echo built; ls -la Godwit.apk
