param([switch]$SkipBind)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
Set-Location $PSScriptRoot
$env:ANDROID_HOME = (Resolve-Path '..\android-sdk').Path
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\28.2.13676358'
$env:GOPATH = (Resolve-Path '..\gopath').Path
$env:GOFLAGS = '-buildvcs=false'
$env:GOCACHE = (Resolve-Path '..\gocache').Path
$env:PATH = "$(Resolve-Path '..\go\bin');$env:GOPATH\bin;$env:PATH"
$env:JAVA_HOME = (Get-Item (Get-Command java.exe).Source).Directory.Parent.FullName
$tools = Join-Path $env:ANDROID_HOME 'build-tools\35.0.0'
$jar = Join-Path $env:ANDROID_HOME 'platforms\android-35\android.jar'
New-Item -ItemType Directory -Force build,build\classes,build\dex | Out-Null
if (-not $SkipBind) {
    gomobile bind '-target=android/arm64,android/arm,android/amd64' -androidapi 23 -o build\bridge.aar ./bridge
}
python -m zipfile -e build\bridge.aar build\binding
javac -encoding UTF-8 --release 8 -classpath "$jar;build\binding\classes.jar" -d build\classes java\MainActivity.java
& "$tools\d8.bat" --release --min-api 23 --lib $jar --output build\dex build\binding\classes.jar (Get-ChildItem build\classes -Recurse -Filter '*.class').FullName
& "$tools\aapt2.exe" link -I $jar --manifest AndroidManifest.xml -o build\base.apk
@"
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED

with ZipFile("build/base.apk", "a") as apk:
    for dex in Path("build/dex").glob("*.dex"):
        apk.write(dex, dex.name, ZIP_DEFLATED)
    for library in Path("build/binding/jni").rglob("*.so"):
        apk.write(library, f"lib/{library.parent.name}/{library.name}", ZIP_STORED)
"@ | python -
& "$tools\zipalign.exe" -f -P 16 4 build\base.apk build\aligned.apk
if (-not (Test-Path release.p12)) {
    $env:VPN_SIGNING_PASSWORD = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    $env:VPN_SIGNING_PASSWORD | Set-Content signing-password.txt -Encoding ascii
    keytool -genkeypair -keystore release.p12 -storepass:env VPN_SIGNING_PASSWORD -alias vpn-transfer -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=VPN Transfer'
} else {
    $env:VPN_SIGNING_PASSWORD = (Get-Content signing-password.txt -Raw).Trim()
}
& "$tools\apksigner.bat" sign --ks release.p12 --ks-key-alias vpn-transfer --ks-pass env:VPN_SIGNING_PASSWORD --out ..\..\outputs\VPN-Transfer-Android.apk build\aligned.apk
& "$tools\apksigner.bat" verify --verbose ..\..\outputs\VPN-Transfer-Android.apk
& "$tools\aapt2.exe" dump badging ..\..\outputs\VPN-Transfer-Android.apk | Select-String 'package:|sdkVersion:|native-code:'

