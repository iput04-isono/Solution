# install_all.ps1
# 接続されている全デバイスにデバッグ APK をインストールするスクリプト
#
# 使い方:
#   cd Solution
#   .\scripts\install_all.ps1

$APK = "app\build\outputs\apk\debug\app-debug.apk"
$PACKAGE = "com.crossvision.f.debug"

Write-Host "=== 鉄骨認識アプリ 全端末インストーラー ===" -ForegroundColor Cyan

# APK が存在するか確認
if (-not (Test-Path $APK)) {
    Write-Host "[BUILD] APK が見つかりません。ビルドを開始します..." -ForegroundColor Yellow
    .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[ERROR] ビルドに失敗しました。" -ForegroundColor Red
        exit 1
    }
}

# 接続デバイスを一覧取得
$devices = adb devices | Select-String "^\S+\s+device$" | ForEach-Object {
    ($_ -split "\s+")[0]
}

if ($devices.Count -eq 0) {
    Write-Host "[ERROR] 接続されているデバイスが見つかりません。" -ForegroundColor Red
    Write-Host "        USB デバッグを有効にして接続してください。"
    exit 1
}

Write-Host "[INFO] 接続デバイス数: $($devices.Count) 台" -ForegroundColor Green
Write-Host ""

$success = 0
$failed  = 0

foreach ($device in $devices) {
    $model = adb -s $device shell getprop ro.product.model 2>$null
    $model = $model.Trim()
    Write-Host "[DEVICE] $device  ($model)" -ForegroundColor White

    $result = adb -s $device install -r $APK 2>&1
    if ($result -match "Success") {
        Write-Host "  -> インストール成功" -ForegroundColor Green
        $success++

        # インストール後にアプリを起動
        adb -s $device shell am start -n "$PACKAGE/com.crossvision.f.ui.login.LoginActivity" | Out-Null
        Write-Host "  -> アプリを起動しました" -ForegroundColor Green
    } else {
        Write-Host "  -> インストール失敗: $result" -ForegroundColor Red
        $failed++
    }
    Write-Host ""
}

Write-Host "=== 結果: 成功 $success 台 / 失敗 $failed 台 ===" -ForegroundColor Cyan
