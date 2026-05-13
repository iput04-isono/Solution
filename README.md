# CrossVision F — 鉄骨文字認識アプリ

> **GroupA 開発** | 鉄骨工程管理システムの Android アプリケーション

鉄骨製品に刻印された製品番号をカメラで撮影し、AI（PaddleOCR）で自動認識・工程登録するシステムです。  
認識した製品コードはサーバーへリアルタイム送信され、オフライン時も端末に保存してオンライン復帰時に自動同期します。

---

## 🆕 prototype_saitoOCR_ver1.0 — 変更点・追加パラメータ（prototype_ver2.1 からの差分）

> ブランチ: `prototype_saitoOCR_ver1.0` | 更新日: 2026-05-13  
> 斎藤案 OCR エンジンへの換装 ＋ 手動回転ボタン追加。UI・照合ロジックは prototype_ver2.1 を継承。  
> **アプリ ID**: `com.crossvision.f.saito`（既存アプリと同時インストール可能）  
> **アプリ名**: 鉄骨認識(saito)

### 変更点一覧

| 項目 | prototype_ver2.1 | prototype_saitoOCR_ver1.0（本ブランチ） |
|---|---|---|
| **コントラスト補正方式** | パーセンタイルクリッピング（上下2%除外）＋ヒストグラムストレッチ | 線形ストレッチ（scale上限2.2倍, bias+8f） |
| **コントラストスキップ閾値** | `CONTRAST_SKIP_THRESHOLD = 5f` | `range < 12f` でスキップ（範囲が狭すぎる場合）|
| **DBNet 入力方式** | 縦横比保持＋グレー(128)パディングで 640×640 | 単純リスケール 640×640（アスペクト比は無視） |
| **向き認識方式** | 0°/90°/180°/270° の4方向を**並列**推論し全比較 | 0° を推論 → 信頼度が十分なら即終了、不十分なら 180° を追加推論（**逐次・早期終了**） |
| **向き判定スコア** | LabelMatcher 編集距離優先（同距離なら信頼度） | 信頼度重みスコア `conf×0.80 + 有効文字率×0.15 + 文字数×0.002` |
| **BFS 検出閾値** | `DET_THRESHOLD = 0.28f` | `threshold = 0.26f`（より低い確率値の文字も検出）|
| **BFS 最小ピクセル数** | `BFS_MIN_PX = 25` | `minPx = 24` |
| **最大検出領域数** | `MAX_REGIONS = 12` | `MAX_POLYGON_REGIONS = 24`（最大2倍の領域を処理）|
| **認識前フィルタ** | なし | `isUsefulCropForOcr()` でクロップ画像の画質を事前チェック（真っ白・低コントラストを除外）|
| **認識後フィルタ** | なし | `isUsefulOcrResult()` で認識テキストの妥当性を事後チェック（空・記号のみを除外）|
| **処理時間計測** | なし | `OcrTiming` クラスで各ステップの処理時間（ms）を記録 |
| **手動回転ボタン** | なし | 認識画面に **「↺ 左90°」「↻ 右90°」** ボタンを追加。プレビューと OCR 入力の両方に適用 |

### 新規パラメータ詳細

#### コントラスト補正（`OcrEngine.kt` — `enhanceContrastForDetection`）

```kotlin
// range = maxLuminance - minLuminance（0〜255）
if (range < 12f) return bitmap          // 範囲が狭い場合はスキップ（変更前 ver2.1: 5f）
val scale = (220f / range).coerceAtMost(2.2f)  // 引き伸ばし倍率の上限 2.2 倍
val bias  = -minL * scale + 8f          // 明るさ底上げ（+8f）
```

| パラメータ | 値 | 意味 |
|---|---|---|
| スキップ閾値 | `12f` | 輝度レンジが 12 未満なら補正しない |
| scale 上限 | `2.2f` | 引き伸ばし過ぎによる白飛びを防止 |
| bias | `+8f` | 暗い画像全体を微量に底上げ |

#### 向き認識スコア（`OcrEngine.kt` — `recognitionScore`）

```kotlin
fun recognitionScore(result: OcrResult): Float {
    val usefulRatio = 有効文字数 / 全文字数   // 英数字・ハイフン・スラッシュの比率
    return result.confidence * 0.80f          // 信頼度（重み 80%）
         + usefulRatio      * 0.15f          // 有効文字率（重み 15%）
         + text.length.coerceAtMost(24) * 0.002f  // 文字数ボーナス（上限 24 文字）
}
```

#### 認識前フィルタ（`isUsefulCropForOcr`）

| チェック条件 | 除外される画像 |
|---|---|
| `brightRatio > 0.96 && darkRatio < 0.002 && contrast < 35` | ほぼ真っ白な領域 |
| `coloredRatio < 0.002 && edgeRatio < 0.003 && contrast < 45` | 色も輪郭もないフラットな領域 |
| `coloredCount < 2 && edgeLikeCount < 3 && contrast < 35` | 情報量が極端に少ない領域 |

#### 認識後フィルタ（`isUsefulOcrResult`）

| チェック条件 | 除外される結果 |
|---|---|
| テキストが空 | 空文字 |
| `"EMPTY"` / `"ERROR"` / `"FORMATERR"` | モデルのエラー出力 |
| 有効文字（英数字・`-`・`/`）比率 < 45% | 記号・ゴミ文字だけの認識 |
| 有効文字数 ≤ 2 かつ信頼度 < 60% | 短すぎ＆低信頼の認識 |

### 手動回転ボタンの仕様

```
[認識画面]
   ┌──────────────────────────────┐
   │    カメラ/ギャラリー画像         │
   │        プレビュー                │
   └──────────────────────────────┘
   [ ↺ 左90° ]   [ ↻ 右90° ]       ← 新規追加（画像選択後に表示）
   [ カメラ ]     [ ギャラリー ]
   [  文字を認識する  ]
```

- ボタンタップごとに `manualRotationDegrees` を ±90° 更新
- プレビュー画像に即時反映（視覚確認できる）
- 「文字を認識する」ボタン押下時に回転を適用した画像を OCR に渡す
- 新しい画像を読み込むと `manualRotationDegrees = 0f` にリセット

---

## リポジトリ

| リポジトリ | ブランチ | 役割 |
|---|---|---|
| [iput04-isono/sevenstar](https://github.com/iput04-isono/sevenstar) | `main` | GroupA 開発リポジトリ（主作業場所） |
| [iput04-isono/Solution](https://github.com/iput04-isono/Solution) | `prototype` | チーム統合ブランチ（リリース相当） |
| [iput04-isono/Solution](https://github.com/iput04-isono/Solution) | `prototype_Ver2.0` | prototype の Ver2.0 スナップショット |
| [iput04-isono/Solution](https://github.com/iput04-isono/Solution) | `prototype_ver2.1` | OCR精度改善・UI強化・バグ修正（Ver2.0 からの差分） |
| [iput04-isono/Solution](https://github.com/iput04-isono/Solution) | `prototype_saitoOCR_ver1.0` | 斎藤案OCRエンジン換装・手動回転ボタン追加（Ver2.1 からの差分） |

---

## システム構成

```mermaid
graph TB
    subgraph Android["📱 Android 端末（CrossVision F）"]
        direction TB
        subgraph UI["UI レイヤー"]
            LC["Login / 工事・工程選択"]
            CAM["Camera / Gallery 撮影"]
            CONF["認識結果確認・修正"]
            HIST["登録履歴表示"]
        end

        subgraph OCR["AI 認識エンジン（ONNX Runtime）"]
            PRE["ImagePreprocessor\n(Resize / Contrast)"]
            DET["DBNet (det.onnx)\nテキスト領域検出"]
            CROP["Perspective Crop\n多角形領域切り出し"]
            REC["SVTR (ppocr_rec.onnx)\n文字認識 (0°/180°)"]
        end

        subgraph MATCH["照合エンジン"]
            LM["LabelMatcher\nLevenshtein 距離計算"]
            VAR["バリアント生成\n(0↔O, 1↔I, etc.)"]
        end

        subgraph DATA["データ管理層"]
            DB[("Room Database (SQLite)")]
            subgraph Tables["Tables"]
                T_REG["registrations (未同期データ)"]
                T_PROD["product_labels (製品コード)"]
                T_CONS["constructions (工事名)"]
                T_PROC["processes (工程名)"]
            end
        end

        subgraph SYNC["同期制御 (WorkManager)"]
            SW["SyncWorker (15分間隔)"]
            SM["SyncManager"]
        end

        RC["Retrofit (HTTP Client)\nAPI Key 認証"]
    end

    subgraph NET["🌐 LAN / Wi-Fi"]
        MDNS["mDNS / Zeroconf\n(_crossvision._tcp.local.)"]
    end

    subgraph SRV["🖥️ FastAPI サーバー (Windows)"]
        direction TB
        API["REST API (Python)"]
        DASH["管理ダッシュボード\n(Realtime Monitor)"]
        STORAGE[("registrations.json\n永続化ストレージ")]
        CSV["CSV Exporter"]
    end

    %% Flows
    CAM --> PRE
    PRE --> DET
    DET --> CROP
    CROP --> REC
    REC --> LM
    LM <--> VAR
    LM --> CONF
    CONF --> DB
    
    DB --> SW
    SW --> SM
    SM --> RC
    
    MDNS -.->|IP/Port 発見| RC
    RC <-->|登録データ送信 / マスター取得| API
    API <--> STORAGE
    API <--> DASH
    DASH --> CSV
```

### 構成の補足

| コンポーネント | 技術 | 説明 |
|---|---|---|
| **UI レイヤー** | Material Components | ログイン、工事・工程選択、カメラ撮影、認識結果の修正・登録、履歴表示を提供 |
| **OCR エンジン** | PP-OCRv4 (DBNet + SVTR) | ONNX Runtime を使用し、端末内でテキスト領域検出と文字認識を完結。リサイズや回転補正の前処理を含む |
| **照合エンジン** | Levenshtein 距離 | OCR 誤認識（0/O, 1/I 等）を正規化し、製品マスターと照合。編集距離 3 以内を許容 |
| **ローカル DB** | Room (SQLite) | オフライン動作のための基盤。登録データ、製品マスター、工事・工程、ユーザー情報を永続化 |
| **バックグラウンド同期** | WorkManager | 15 分間隔で未送信データを自動送信し、24 時間ごとに製品マスターを自動更新 |
| **サーバー自動発見** | Zeroconf (mDNS) | ローカルネットワーク内のサーバーを自動検出し、IP 手入力を不要にする |
| **バックエンド** | FastAPI (Python) | 登録データの受信（registrations.json）、管理ダッシュボード、CSV 書き出しを担当 |

---

## 動作確認済み環境


| 項目 | バージョン / 内容 |
|---|---|
| Android Studio | Hedgehog 以降推奨 |
| JDK | 17 以上 |
| Kotlin | 1.9.24 |
| compileSdk | 35 |
| minSdk | 24（Android 7.0 以上） |
| 実機テスト端末 | Samsung Galaxy S20 Ultra（Android 13） |

---

## セットアップ手順（初回のみ）

### Step 1. リポジトリを取得

```bash
git clone -b prototype https://github.com/iput04-isono/Solution.git
cd Solution
```

### Step 2. Android Studio で開く

1. Android Studio を起動
2. `File → Open` で `Solution` フォルダを選択
3. Gradle Sync が自動で始まります（`Sync Now` を押す）

> Sync が始まらない場合は `File → Sync Project with Gradle Files`

### Step 3. エミュレーターを使う場合（実機なしで試したい場合）

**AVD（仮想デバイス）の作成：**

1. Android Studio の `Tools → Device Manager` を開く
2. `Create Device` をクリック
3. 以下の設定を推奨：

| 項目 | 推奨値 |
|---|---|
| デバイス | Pixel 6（または任意） |
| System Image | **x86_64** の API 28 以上（例：API 34） |
| RAM | **4096 MB 以上**（OCR モデルが大きいため） |
| Internal Storage | 4096 MB 以上 |

> ⚠️ **注意**：x86_64 イメージを選んでください。arm64 イメージはエミュレーターでの動作が遅くなります。

4. `Finish` でデバイスを作成
5. Device Manager の ▶ ボタンでエミュレーターを起動

**エミュレーターでのカメラ画像テスト：**

エミュレーターはカメラが仮想（チェッカー柄など）のため、実際の鉄骨画像で認識テストをするには **ギャラリーから画像を選択** してください。

```
テスト画像を端末に転送する方法：
  1. エミュレーターの「...（拡張コントロール）」→「Camera」でバーチャルシーンを変更、
     または
  2. エミュレーターのウィンドウにファイルをドラッグ＆ドロップ
     → フォトアプリのギャラリーに追加されます
```

---

### Step 4. USB デバッグを有効にする（実機テストの場合）

1. スマートフォンの `設定 → 端末情報 → ビルド番号` を 7 回タップ  
   → 「開発者向けオプション」が有効になります
2. `設定 → 開発者向けオプション → USB デバッグ` をオン
3. USB ケーブルで PC に接続し、スマートフォン側で「接続を許可」を選択

> **Windows の場合**：Samsung 端末は [Samsung USB Driver](https://developer.samsung.com/mobile/android-usb-driver.html) の別途インストールが必要です

### Step 5. ビルド＆インストール

**Android Studio から実行する場合：**

1. 上部のデバイス選択プルダウンで接続した実機を選択
2. ▶ ボタン（Run）を押す

**コマンドラインから実行する場合：**

```bash
# Windows
.\gradlew.bat installDebug

# Mac / Linux
./gradlew installDebug
```

---

## サーバー（バックエンド）のセットアップ

本プロジェクトは **FastAPI** ベースの管理サーバーを同梱しています。Android アプリからのデータ受信、CSV 書き出し、リアルタイムダッシュボードを提供します。

### Step 5. サーバーの起動

1. Python 3.10 以上がインストールされていることを確認
2. 依存ライブラリのインストール:
   ```bash
   cd server
   pip install -r requirements.txt
   ```
3. サーバー起動:
   ```bash
   # ポート 5000 で起動（Android アプリのデフォルト接続先）
   python main.py
   # または
   uvicorn main:app --host 0.0.0.0 --port 5000 --reload
   ```

### サーバーの主要機能

| 機能 | 説明 |
|---|---|
| **自動発見 (mDNS/Zeroconf)** | 起動と同時に `_crossvision._tcp.local.` でサービスを広告。アプリが IP アドレス不要で自動接続 |
| **管理ダッシュボード** | `http://localhost:5000/admin` でリアルタイムに登録状況を確認。<br>単一削除・一括削除機能、工事名・工程名による検索機能を搭載 |
| **API キー認証** | `X-API-KEY: cvf_7s_9922_zrkp_8x11` ヘッダーによる認証 |
| **CSV エクスポート** | `GET /api/export/csv` で Excel 対応の CSV をダウンロード |

### サーバー API エンドポイント一覧

| メソッド | パス | 説明 | 認証 |
|---|---|---|---|
| `POST` | `/api/registrations` | 登録データ受信・保存 | ✅ 必要 |
| `GET` | `/api/registrations` | 登録データ一覧取得 | ❌ 不要 |
| `DELETE` | `/api/registrations/{id}` | 登録データの単一削除 | ✅ 必要 |
| `DELETE` | `/api/registrations` | 登録データの一括削除 | ✅ 必要 |
| `GET` | `/api/export/csv` | CSV ダウンロード | ❌ 不要 |
| `GET` | `/health` | サーバー死活確認 | ❌ 不要 |
| `GET` | `/admin` | 管理ダッシュボード | ❌ 不要 |

---

## アプリの使い方

### ログイン

アプリ起動後、以下のいずれかでログインしてください。

| ユーザー ID | パスワード | 権限 |
|---|---|---|
| `admin` | `admin123` | 管理者 |
| `user01` | `pass01` | 作業員 |
| `user02` | `pass02` | 作業員 |

### 撮影・文字認識

1. **工事・工程を選択**してから認識画面へ進む
2. **カメラ** または **ギャラリー** から画像を選択
3. 認識結果確認画面で以下を確認：

```
┌───────────────────────────────┐
│  認識領域オーバーレイ画像       │  ← どの文字を読んだか視覚的に確認
│  （緑:高信頼度 / 赤:低信頼度）  │
├───────────────────────────────┤
│  【登録候補】                   │  ← 正解ラベルと一致（編集距離 ≤ 3）
│  ✓ B1Sb30N-7A  (距離:1, 82%)  │
│  ✓ H150x150x7  (完全一致, 91%)│
├───────────────────────────────┤
│  参考：一致しなかった認識結果    │  ← 距離 > 3（登録されません）
│  ・AB3CX  (信頼度:45%)         │
└───────────────────────────────┘
```

3. 認識結果確認画面で内容を確認し、「登録」ボタンを押す

### UI の便利な機能

- **再読み込み（同期）**: カテゴリ選択の横にある 🔄 アイコンをタップすると、最新のマスターデータを手動で再読み込みします。
- **ログアウト**: 🚪 アイコンからログイン画面に戻れます。
- **自動同期**: ネットワークが不安定な場所で登録しても、オンライン復帰時にバックグラウンドで自動送信されます（WorkManager による 15 分間隔の定期同期）。
- **製品コード自動更新**: サーバーの最新製品コードリストを 24 時間ごとに自動取得し、ローカル DB を更新します。

---

## OCR モデルファイルについて

以下のファイルが `app/src/main/assets/` に含まれています（追加作業不要）。

| ファイル | 内容 |
|---|---|
| `det.onnx` | テキスト領域検出モデル（PP-OCRv4 DBNet） |
| `ppocr_rec.onnx` | 文字認識モデル（PP-OCRv4 SVTR mobile） |
| `dict.txt` | 認識文字辞書 |
| `product_labels.txt` | 製品コードマスター初期データ（1017 件）。アプリ起動後はサーバー同期で Room DB に保存され、DB が優先して使われる |

> ⚠️ `det.onnx` は 83MB のため、GitHub の推奨上限（50MB）を超えています。  
> Git Large File Storage（LFS）への移行を今後検討してください。

---

## 文字認識技術の詳細

本アプリが採用している AI 文字認識（OCR）の処理フローと、各ステップで用いている技術を解説します。

### 処理フロー全体

```
[カメラ / ギャラリー画像]
        │  ★ 撮影直前に端末の回転を再取得（Ver2.1改善）
        ▼
① 画像前処理（ImagePreprocessor）
  　長辺を 1280px にリサイズ・コントラスト強調
        │
        ▼
② テキスト領域検出（OcrEngine ― DBNet）
  　★ 縦横比を保ったままグレーパディングで 640×640 に収める（Ver2.1改善）
  　ニューラルネットで文字のある確率マップを生成
  　→ BFS で連結成分を抽出 → 多角形（ポリゴン）として領域を確定
        │
        ▼
③ コントラスト強化（enhanceContrast）
  　★ パーセンタイルクリッピング（上下2%除外）でヒストグラムストレッチ（Ver2.1改善）
  　★ スキップ閾値を 20f → 5f に緩和（錆による微小コントラストも処理）
        │
        ▼
④ 文字認識（OcrEngine ― SVTR）
  　★ アスペクト比が曖昧な領域は 0°/90°/180°/270° の4方向を並列推論（Ver2.1改善）
  　　　明確な横長(ratio>2.0)→ 0°/180°、明確な縦長(ratio<0.5)→ 90°/270°
  　→ LabelMatcher の編集距離優先で最良の向きを採用
  　★ 採用した角度でクロップ画像を回転し確認用に保存（Ver2.1改善）
        │
        ▼
⑤ ラベルマッチング（LabelMatcher）
  　認識テキストと製品コードマスター（Room DB 優先 / assets フォールバック）を比較
  　→ 編集距離 ≤ 3 なら「登録候補」、4 以上なら「参考」に分類
        │
        ▼
⑥ 結果表示（ConfirmActivity）
  　★ オーバーレイ画像（300dp に拡大）＋ 登録候補（各カードにクロップ画像表示）＋ 参考一覧（Ver2.1改善）
```

---

### ① 画像前処理

| 処理 | 内容 |
|---|---|
| **リサイズ** | 長辺を最大 1280px に統一。短辺は縦横比を維持してスケール |
| **コントラスト強調** | 屋外・逆光環境での刻印文字の視認性を改善 |
| **EXIF 回転補正** | スマートフォンのカメラは向きを EXIF タグで記録する。このタグを読み取り、画像を正しい向きに回転させてから認識に渡す |

> **ポイント**：スマートフォンのカメラ画像は「保存時に向きを回転させず EXIF に記録する」仕様のため、EXIF を無視すると 90° 回転した状態で認識されてしまう。本アプリではこれを自動補正している。

---

### ② テキスト領域検出（DBNet）

#### DBNet とは

**DBNet（Differentiable Binarization Network）** は、中国の AI 研究機関 Baidu が開発した **PaddleOCR** に含まれるテキスト検出モデルです。

通常の二値化（白黒化）では固定の閾値を使うのに対し、DBNet は「**どのピクセルが文字領域か**」を表す確率マップを **ニューラルネットワーク自身が学習**します。この確率マップを使って、斜め・湾曲した文字にも対応できる **多角形（ポリゴン）** として領域を切り出します。

```
入力画像 → [DBNet モデル（det.onnx）] → 確率マップ（0.0〜1.0）
                                              │
                                  閾値以上のピクセルを BFS で連結
                                              │
                                    連結成分ごとに多角形を生成
                                              │
                                    UNCLIP で領域を少し広げる
                                              │
                                    切り取り済みのテキスト領域群
```

#### 主要パラメータ

| パラメータ | 値 | 意味 |
|---|---|---|
| `DET_SIZE` | 640px | モデルへ入力する画像サイズ |
| `DET_THRESHOLD` | 0.28 | 確率マップの閾値（低いほど小さな文字を拾いやすい） |
| `BFS_MIN_PX` | 25px | 連結成分の最小ピクセル数（雑音除去） |
| `MIN_POLY_AREA` | 100px² | 有効な領域の最小面積（極小の誤検出を除外） |
| `UNCLIP_RATIO` | 2.0 | 検出した領域を広げる比率（文字の端が切れないように） |
| `MAX_REGIONS` | 12 | 1 枚の画像から処理する最大領域数 |

> **Ver2.1 改善 — 縦横比保持パディング**：Ver2.0 では入力画像を単純に 640×640 に引き伸ばしていたため、縦長・横長の画像でアスペクト比が崩れ検出精度が低下していた。Ver2.1 では縦横比を維持したままグレー（128）でパディングして 640×640 に収める方式に変更した。ヒートマップ座標からの逆変換もパディング量・スケールを考慮して補正している。

> **UNCLIP（領域の拡張）について**：DBNet が検出する領域は文字ギリギリに収まることが多い。`UNCLIP_RATIO` を大きくすることで認識精度が上がる一方、隣接する文字と混在するリスクがある。鉄骨の刻印は文字間隔が広いため、2.0 に設定している。

#### 多角形の切り出し（Perspective Crop）

多角形の4頂点を **射影変換（Perspective Transform）** で長方形に変換してから認識モデルへ渡します。Android の `Matrix.setPolyToPoly` を使用しています。

```
 ╱─────╲           ┌─────────┐
╱ 傾いた ╲  →変換→  │ 正面から │
╲ 文字領域╱         │ 見た文字 │
 ╲─────╱           └─────────┘
```

---

### ③ 文字認識（SVTR）

#### SVTR とは

**SVTR（Single Visual model for scene Text Recognition）** は、PaddleOCR に含まれる文字認識モデルです。検出された各テキスト領域から、実際の文字列を出力します。

- 入力：横長に正規化した領域画像（高さ 48px、幅は動的に変化）
- 出力：文字列と各文字の確信度（0.0〜1.0）

#### 回転対応（向き自動判定）— Ver2.1 改善

鉄骨の刻印は撮影角度・端末の向きにより様々な方向で映り込む。Ver2.1 ではアスペクト比に応じて試す方向数を動的に変化させる。

| 切り取り領域の形状 | ratio（幅/高さ） | 試す方向 |
|---|---|---|
| 明確な横長 | > 2.0 | 0°、180° の 2 方向 |
| 明確な縦長 | < 0.5 | 90°、270° の 2 方向 |
| 曖昧（正方形に近い） | 0.5〜2.0 | **0°/90°/180°/270° の 4 方向すべて** |

全方向を並列推論し、**LabelMatcher の編集距離が最小（同距離なら確信度最大）** の結果を採用します。

```kotlin
val angles = when {
    ratio > 2.0f -> floatArrayOf(0f, 180f)         // 横長：2方向
    ratio < 0.5f -> floatArrayOf(90f, 270f)         // 縦長：2方向
    else         -> floatArrayOf(0f, 90f, 180f, 270f) // 曖昧：4方向すべて
}
// 全方向を並列推論 → 編集距離最小・確信度最大を選択
```

採用した角度でクロップ画像を回転して保存し、認識結果確認画面で **「どの向きで読んだか」** を視覚的に確認できます。

---

### ④ ラベルマッチング（Levenshtein 距離）

> **データソース**：製品コードは Room DB（サーバー同期済み）を優先して読み込みます。DB が空の場合は `assets/product_labels.txt`（初期データ 1017 件）にフォールバックします。サーバー同期は WorkManager が 24 時間ごとにバックグラウンドで自動実行します。

#### 製品コードマスター同期フロー

```
[起動 / WorkManager 定期実行]
         │
         ▼
 ネットワーク確認 ── 未接続 ──→ スキップ（-1 を返す）
         │ 接続あり
         ▼
 前回同期から 24 時間以内？ ── YES ──→ スキップ（-1 を返す）
         │ NO
         ▼
 GET /api/product-labels でサーバーから取得
（現在はモック: assets/product_labels.txt を参照）
         │
         ▼
 product_labels テーブルを全削除 → 新リストを一括挿入
         │
         ▼
 LabelMatcher が DB から最新リストを読み込み
```

#### Levenshtein 距離（編集距離）とは

2 つの文字列の「**似ている度合い**」を数値化する方法です。  
「1 文字の追加・削除・置換」を何回行えば一方の文字列に変換できるかを距離として表します。

```
例）OCR 認識結果 "B1Sb30N-7B"
    正解ラベル    "B1Sb30N-7A"
    → 末尾 1 文字だけ異なる → 距離 = 1（1 回の置換）

例）OCR 認識結果 "H15OX15OX7"（O と 0 を混同）
    正解ラベル    "H150X150X7"
    → 2 文字の混同 → 距離 = 2
```

#### 判定ルール

| 編集距離 | 判定 | 表示 |
|---|---|---|
| 0（完全一致） | 登録候補 ◎ | 認識結果確認画面の上部 |
| 1〜3（近い一致） | 登録候補 ○ | 同上（正解ラベルを表示） |
| 4 以上（遠い） | 参考情報 | 画面下部の別枠に表示 |
| ラベルなし | 参考情報 | 同上 |

#### OCR 誤認識への対策（バリアント生成）

鉄骨刻印で混同しやすい文字ペアを事前に定義し、OCR 出力を正規化してから距離計算しています。

| OCR が間違えやすいパターン | 対応 |
|---|---|
| `0`（ゼロ）と `O`（英字オー） | 両方向に変換したバリアントを生成 |
| `1`（数字）と `I`（英字アイ） | 同上 |
| `5` と `S` | 同上 |
| `8` と `B` | 同上 |

---

---

### ③-補足 コントラスト強化（Ver2.1 改善）

切り出した各テキスト領域に対して、認識モデルへ渡す前にコントラストを調整します。

| パラメータ | Ver2.0 | Ver2.1 |
|---|---|---|
| `CONTRAST_SKIP_THRESHOLD` | `20f`（差が小さければスキップ） | **`5f`**（錆で輝度差が小さくても処理する） |
| クリッピング | なし（最小・最大をそのまま使用） | **上下 2% の外れ値を除外**してから引き伸ばし |

**なぜ変更したか：**
- `20f` では錆びた鉄骨のように全体が茶色に偏った画像でスキップされ、文字が認識しにくいまま推論に渡っていた
- 反射光が1点だけ白飛びしている場合、クリッピングなしでは `max=255` となりストレッチ効果がほぼゼロになっていた

---

### ⑤ 認識結果の可視化（Ver2.1 強化）

検出した各テキスト領域を元画像に色付きで重ねて表示することで、「どの文字を認識したか」を視覚的に確認できます。

| 色 | 意味（信頼度） |
|---|---|
| 緑 | 高信頼度（≥ 60%） |
| 黄 | 中信頼度（30〜60%） |
| 赤 | 低信頼度（< 30%） |

数字バッジ（①②③…）で結果リストとの対応を示しています。

**Ver2.1 追加 — 各結果カードにクロップ画像を表示：**

```
┌─────────────────────────────────────┐
│  [クロップ画像: 認識した文字領域の横長画像]  │ ← 回転補正済・横長を強制
├─────────────────────────────────────┤
│ ✓  B1Sb30N-7A                 ✏️ 🗑  │
│    認識テキスト: BISbBON-7A           │
│    [信頼度:0.52  距離:2  OCR:...]    │
└─────────────────────────────────────┘
```

- オーバーレイ画像の高さを **220dp → 300dp** に拡大
- 各カードのクロップ画像は `adjustViewBounds` でアスペクト比を維持（最大120dp）

---

## プロジェクト構成

```
app/src/main/java/com/crossvision/f/
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt        # Room DB 定義（v2）・マイグレーション管理
│   │   ├── ProductLabelDao.kt    # 製品コードマスター DAO
│   │   ├── ConstructionDao.kt    # 工事データ DAO
│   │   ├── ProcessDao.kt         # 工程データ DAO
│   │   ├── RegistrationDao.kt    # 登録データ DAO
│   │   └── UserDao.kt            # ユーザーデータ DAO
│   ├── model/
│   │   ├── ProductLabel.kt       # 製品コードマスターエンティティ
│   │   ├── Construction.kt       # 工事エンティティ
│   │   ├── Process.kt            # 工程エンティティ
│   │   ├── Registration.kt       # 登録エンティティ
│   │   └── User.kt               # ユーザーエンティティ
│   └── repository/
│       └── AppRepository.kt      # DB ↔ UI の橋渡し（製品コード操作含む）
├── ocr/
│   ├── OcrEngine.kt              # PaddleOCR 推論（DBNet + SVTR）
│   ├── LabelMatcher.kt           # Levenshtein 距離によるラベル照合（DB 優先）
│   ├── OcrProcessor.kt           # 認識結果を登録候補 / 参考に分類
│   ├── OcrResult.kt              # 認識結果データクラス
│   ├── ProductCodeValidator.kt   # 製品コード形式バリデーション
│   └── ImagePreprocessor.kt      # リサイズ前処理（長辺 1280px）
├── sync/
│   ├── SyncWorker.kt             # WorkManager バックグラウンドタスク
│   └── SyncManager.kt            # 登録データ同期 + 製品コードマスター更新
├── ui/
│   ├── login/                    # ログイン画面
│   ├── process/                  # 工事・工程選択画面
│   ├── camera/                   # CameraX カメラ画面
│   ├── recognize/                # 画像選択・OCR 実行画面
│   ├── confirm/                  # 認識結果確認・登録画面
│   ├── register/                 # 登録完了画面
│   └── library/                  # 登録履歴一覧画面
└── CrossVisionApp.kt             # Application クラス（WorkManager 初期化）
```

**テスト・スクリプト：**

```
app/src/androidTest/java/com/crossvision/f/
└── ProductLabelSyncTest.kt  # 製品コード同期の実機インストゥルメンテッドテスト（5ケース）

scripts/
├── install_all.ps1          # 接続全デバイスへ一括インストール
└── test_label_sync.ps1      # 製品コード同期の自動テスト（PowerShell）

server/
├── main.py                  # 開発用ローカルサーバー（FastAPI）
└── requirements.txt         # Python 依存ライブラリ
```

---

## ブランチ運用ルール

```
[Solution リポジトリ]
prototype            ← 動作確認済みの統合ブランチ（リリース相当）
prototype_Ver2.0     ← prototype の Ver2.0 時点のスナップショット
prototype_ver2.1     ← OCR精度改善・UI強化・バグ修正（Ver2.0 からの差分）
prototype_develop    ← prototype への統合前の開発ブランチ
feature/*            ← 機能ごとの作業ブランチ

[sevenstar リポジトリ]
main                 ← GroupA 主作業ブランチ（= Solution/prototype と同期）
```

### リポジトリ間の同期

```bash
# sevenstar の main に作業内容をプッシュ
git push origin main

# Solution/prototype にも同期（チームへの反映）
git push upstream main:prototype

# upstream（Solution）の最新を取り込む
git fetch upstream
git merge upstream/prototype
```

### 機能開発フロー

```bash
# 1. prototype_develop から作業ブランチを切る
git fetch upstream
git checkout -b feature/機能名 upstream/prototype_develop

# 2. 作業してコミット
git add .
git commit -m "feat: 機能の説明"

# 3. sevenstar へプッシュ → Solution へ PR
git push origin feature/機能名
# → PR: feature/機能名 → prototype_develop（Solution）
# → 動作確認後、prototype_develop → prototype へ PR
```

### コミットメッセージのルール

| プレフィックス | 用途 |
|---|---|
| `feat:` | 新機能追加 |
| `fix:` | バグ修正 |
| `docs:` | ドキュメント変更 |
| `refactor:` | リファクタリング |
| `test:` | テスト追加 |
| `chore:` | Gradle・設定変更 |

---

## 使用ライブラリ

| ライブラリ | バージョン | 用途 |
|---|---|---|
| androidx.core-ktx | 1.15.0 | Kotlin 拡張 |
| androidx.appcompat | 1.7.0 | 後方互換 UI |
| material | 1.12.0 | マテリアルデザイン |
| androidx.lifecycle | 2.8.7 | ViewModel / LiveData |
| Room | 2.6.1 | ローカル DB |
| CameraX | 1.3.4 | カメラ制御 |
| WorkManager | 2.9.1 | バックグラウンド同期 |
| Retrofit2 | 2.9.0 | HTTP 通信 |
| OkHttp | 4.12.0 | HTTP クライアント |
| ONNX Runtime Android | 1.17.1 | AI モデル推論（OCR） |
| Kotlinx Coroutines | 1.8.1 | 非同期処理 |

---

## 更新履歴

| バージョン | 日付 | 内容 |
|---|---|---|
| prototype_saitoOCR_ver1.0 | 2026-05-13 | 斎藤案OCRエンジン換装（線形コントラスト・逐次向き認識・画質フィルタ追加）、手動回転ボタン追加（±90°）、別アプリID(.saito)で共存インストール対応 |
| prototype_ver2.1 | 2026-05-13 | OCR精度改善（コントラスト・4方向認識・DBNetパディング）、UI強化（クロップ画像表示・オーバーレイ拡大）、カメラ回転修正、工程選択バグ修正 |
| Ver 2.0 | 2026-04-24 | 製品コードマスターの Room DB 管理化・サーバー自動同期機能を統合（GroupA × チームメンバー機能マージ） |
| Ver 1.x | 〜2026-04-23 | OCR 認識・登録・履歴・オフライン同期の基本機能実装 |

---

## 関連リソース

- [画像処理デモ（スタンドアロン）](./image-preprocessing/) - OCR 単体の動作確認用
- [sevenstar リポジトリ](https://github.com/iput04-isono/sevenstar) - GroupA 開発リポジトリ
- [Solution/prototype](https://github.com/iput04-isono/Solution/tree/prototype) - チーム統合ブランチ
- 正解ラベルデータ・テスト画像 → チーム共有の Google Drive を参照
