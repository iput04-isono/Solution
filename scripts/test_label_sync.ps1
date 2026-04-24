# test_label_sync.ps1
# 製品コードマスターのサーバー同期をリアルデバイス上で強制実行し、
# logcat でその結果を自動確認するスクリプト
#
# 使い方:
#   cd Solution
#   .\scripts\test_label_sync.ps1
#
# 引数（省略可）:
#   -Device <serial>  特定デバイスのシリアル番号（省略時は最初の1台）

param(
    [string]$Device = ""
)

$PACKAGE    = "com.crossvision.f.debug"
$TIMEOUT_S  = 30   # 同期完了を待つ最大秒数

Write-Host "=== 製品コード同期テスト ===" -ForegroundColor Cyan

# デバイス選択
if ($Device -eq "") {
    $devices = adb devices | Select-String "^\S+\s+device$" | ForEach-Object { ($_ -split "\s+")[0] }
    if ($devices.Count -eq 0) {
        Write-Host "[ERROR] デバイスが接続されていません。" -ForegroundColor Red; exit 1
    }
    $Device = $devices[0]
}

$model = (adb -s $Device shell getprop ro.product.model).Trim()
Write-Host "[DEVICE] $Device ($model)" -ForegroundColor White
Write-Host ""

# ステップ1: 前回の同期タイムスタンプをクリア（24時間スキップを回避）
Write-Host "[STEP 1] DB の製品コードを削除して同期を強制実行できる状態にします..."
adb -s $Device shell "run-as $PACKAGE sqlite3 /data/data/$PACKAGE/databases/crossvision_db 'DELETE FROM product_labels;'" 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host "         -> DB クリア成功" -ForegroundColor Green
} else {
    Write-Host "         -> DB クリア失敗（run-as 未対応端末）。スキップして継続します。" -ForegroundColor Yellow
}
Write-Host ""

# ステップ2: logcat をクリア
Write-Host "[STEP 2] logcat をリセットします..."
adb -s $Device logcat -c
Write-Host "         -> OK" -ForegroundColor Green
Write-Host ""

# ステップ3: WorkManager の即時同期をトリガー
Write-Host "[STEP 3] 同期をトリガーします（WorkManager Diagnostic）..."
adb -s $Device shell am broadcast `
    -a "androidx.work.diagnostics.REQUEST_DIAGNOSTICS" `
    -p $PACKAGE 2>$null | Out-Null

# SyncWorker を直接 JobScheduler 経由で強制実行
adb -s $Device shell cmd jobscheduler run -f $PACKAGE -1 2>$null | Out-Null
Write-Host "         -> トリガー送信完了" -ForegroundColor Green
Write-Host ""

# ステップ4: logcat を監視して結果を確認
Write-Host "[STEP 4] logcat を監視中... （最大 ${TIMEOUT_S}秒）" -ForegroundColor Yellow
Write-Host "         'Ctrl+C' で中断できます"
Write-Host ""

$found_sync_start  = $false
$found_label_sync  = $false
$found_label_count = 0
$deadline          = (Get-Date).AddSeconds($TIMEOUT_S)

$job = Start-Job -ScriptBlock {
    param($dev)
    adb -s $dev logcat -v time SyncWorker:D SyncManager:I LabelMatcher:D *:S 2>&1
} -ArgumentList $Device

try {
    while ((Get-Date) -lt $deadline) {
        $output = Receive-Job -Job $job
        foreach ($line in $output) {
            if ($line -match "同期処理を開始") {
                $found_sync_start = $true
                Write-Host "  [LOG] $line" -ForegroundColor Gray
            }
            if ($line -match "製品コード同期完了") {
                $found_label_sync = $true
                if ($line -match "(\d+)件") { $found_label_count = $matches[1] }
                Write-Host "  [LOG] $line" -ForegroundColor Green
            }
            if ($line -match "ラベル読み込み完了") {
                Write-Host "  [LOG] $line" -ForegroundColor Green
            }
            if ($line -match "同期スキップ|同期エラー") {
                Write-Host "  [LOG] $line" -ForegroundColor Yellow
            }
        }

        if ($found_label_sync) { break }
        Start-Sleep -Milliseconds 500
    }
} finally {
    Stop-Job -Job $job
    Remove-Job -Job $job
}

Write-Host ""
Write-Host "=== テスト結果 ===" -ForegroundColor Cyan

if ($found_label_sync) {
    Write-Host "[OK] 製品コード同期が正常に完了しました。更新件数: ${found_label_count}件" -ForegroundColor Green
} elseif ($found_sync_start) {
    Write-Host "[NG] 同期は開始されましたが、製品コード同期ログが見つかりませんでした。" -ForegroundColor Yellow
    Write-Host "     adb -s $Device logcat -v time SyncManager:I *:S  で詳細を確認してください。"
} else {
    Write-Host "[NG] SyncWorker が起動しませんでした。" -ForegroundColor Red
    Write-Host "     アプリが前面にあり Wi-Fi 接続されているか確認してください。"
    Write-Host "     または Android Studio の App Inspection > Background Task Inspector で"
    Write-Host "     SyncWorker を手動実行してください。"
}
