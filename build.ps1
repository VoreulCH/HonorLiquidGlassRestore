$ErrorActionPreference = "Stop"

$root   = "d:\Users\Administrator\Documents\6ac5cf7d3718c824938055b6\lg"
$tools  = "$root\tools"
$bt     = "$tools\bt\android-14"
$plat   = "$tools\p36\android-36\android.jar"
$xposed = "$tools\api-82.jar"
$ecj    = "$tools\ecj.jar"
$proj   = "$root\module"
$build  = "$proj\build"
$ks     = "$proj\lgr.keystore"

Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$build\classes" | Out-Null

Write-Host "== 1/6 compile (ECJ) =="
java -jar $ecj -source 8 -target 8 -nowarn -proc:none -encoding UTF-8 `
    -classpath "$plat;$xposed" `
    -d "$build\classes" `
    "$proj\src\io\github\voreulch\liquidglass\MainHook.java"
if ($LASTEXITCODE -ne 0) { throw "compile failed" }

Write-Host "== 2/6 dex (d8) =="
$classFiles = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --release --lib $plat --classpath $xposed --output $build @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== 3/6 aapt2 compile+link =="
& "$bt\aapt2.exe" compile --dir "$proj\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }
& "$bt\aapt2.exe" link -o "$build\base.apk" `
    -I $plat `
    --manifest "$proj\AndroidManifest.xml" `
    -A "$proj\assets" `
    -R "$build\res.zip" `
    --min-sdk-version 28 --target-sdk-version 35 `
    --version-code 37 --version-name 2.5.18 `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== 4/6 inject classes.dex =="
python "$proj\inject_dex.py" "$build\base.apk" "$build\classes.dex" "$build\full.apk"

Write-Host "== 5/6 zipalign =="
& "$bt\zipalign.exe" -f 4 "$build\full.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "== 6/6 sign =="
$jhome = Split-Path (Split-Path (Get-Command java).Source)
if (!(Test-Path $ks)) {
    & "$jhome\bin\keytool.exe" -genkeypair -keystore $ks -alias lgr `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass liquidglass -keypass liquidglass `
        -dname "CN=Liquid Glass Restorer"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& "$bt\apksigner.bat" sign --ks $ks --ks-key-alias lgr `
    --ks-pass pass:liquidglass --key-pass pass:liquidglass `
    --out "$proj\HonorLiquidGlassRestore.apk" "$build\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host ""
Write-Host "DONE -> $proj\HonorLiquidGlassRestore.apk"
