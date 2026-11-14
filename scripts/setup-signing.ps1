# Creates the Android signing key and prints the GitHub Secrets to register.
#
# Usage (one time, on your own machine):
#   .\scripts\setup-signing.ps1
#
# The key file is written outside the repository by default. Never commit it:
# anyone holding it can publish an update that Android will trust as yours.
param(
    [string]$OutDir = "$env:USERPROFILE\.instant-mic-signing",
    [string]$Alias = "instant-mic"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    throw "keytool が見つかりません。JDK をインストールして PATH に追加してください。"
}

$keytool = (Get-Command keytool).Source
$store = Join-Path $OutDir "release.jks"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

if (Test-Path $store) {
    Write-Host "既存の鍵があります: $store"
    Write-Host "作り直すと、既存の APK に上書きインストールできなくなります。"
    $answer = Read-Host "作り直しますか？ (yes/N)"
    if ($answer -ne "yes") {
        Write-Host "中止しました。"
        exit 0
    }
    Remove-Item $store -Force
}

$storePass = Read-Host "キーストアのパスワード（8文字以上）" -AsSecureString
$storePlain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($storePass))
$keyPass = Read-Host "鍵のパスワード（Enter でキーストアと同じ）" -AsSecureString
$keyPlain = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($keyPass))
if ([string]::IsNullOrEmpty($keyPlain)) { $keyPlain = $storePlain }

& $keytool -genkeypair `
    -keystore $store `
    -alias $Alias `
    -keyalg RSA -keysize 4096 -validity 10000 `
    -storepass $storePlain -keypass $keyPlain `
    -dname "CN=Instant Mic, O=instant-mic, C=JP"

if ($LASTEXITCODE -ne 0) { throw "keytool が失敗しました。" }

Write-Host ""
Write-Host "鍵を作成しました: $store"
Write-Host ""
Write-Host "GitHub の Settings → Secrets and variables → Actions に次を登録してください。"
Write-Host ""

$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($store))
Write-Host "INSTANT_MIC_KEYSTORE_BASE64"
Write-Host ("=" * 72)
Write-Host $base64
Write-Host ("=" * 72)
Write-Host ""
Write-Host "INSTANT_MIC_KEYSTORE_PASSWORD = (入力したキーストアのパスワード)"
Write-Host "INSTANT_MIC_KEY_ALIAS        = $Alias"
Write-Host "INSTANT_MIC_KEY_PASSWORD     = (入力した鍵のパスワード)"
Write-Host ""
Write-Host "重要: この鍵を失うと、既存ユーザーへ更新を配布できなくなります。"
Write-Host "      安全な場所にバックアップしてください（リポジトリには絶対に置かない）。"
