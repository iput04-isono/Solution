# 鉄骨文字認識アプリ - GroupA

鉄骨工程管理システムの Android アプリケーションです。  
鉄骨製品に刻印された製品番号をカメラで撮影し、AI（PaddleOCR）で自動認識・工程登録するシステムです。

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
git clone https://github.com/iput04-isono/Solution.git
cd Solution
git checkout prototype
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

### Step 4. ビルド＆インストール

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

4. 登録候補にチェックを入れて **「登録」** ボタンを押す

---

## OCR モデルファイルについて

以下のファイルが `app/src/main/assets/` に含まれています（追加作業不要）。

| ファイル | 内容 |
|---|---|
| `det.onnx` | テキスト領域検出モデル（PP-OCRv4 DBNet） |
| `ppocr_rec.onnx` | 文字認識モデル（PP-OCRv4 SVTR mobile） |
| `dict.txt` | 認識文字辞書 |
| `product_labels.txt` | 正解ラベルマスター（1017 件） |

> ⚠️ `det.onnx` は 83MB のため、GitHub の推奨上限（50MB）を超えています。  
> Git Large File Storage（LFS）への移行を今後検討してください。

---

## プロジェクト構成

```
app/src/main/java/com/crossvision/f/
├── data/
│   ├── local/          # Room DB（ユーザー・工事・工程・登録データ）
│   ├── model/          # データモデル
│   └── repository/     # DB ↔ UI の橋渡し
├── ocr/
│   ├── OcrEngine.kt    # PaddleOCR 推論（DBNet + SVTR）
│   ├── LabelMatcher.kt # Levenshtein 距離によるラベル照合
│   ├── OcrProcessor.kt # 認識結果を登録候補 / 参考に分類
│   └── ImagePreprocessor.kt # リサイズ前処理（長辺 1280px）
├── sync/
│   ├── SyncWorker.kt   # WorkManager によるサーバー自動同期
│   └── SyncManager.kt
├── ui/
│   ├── login/          # ログイン画面
│   ├── process/        # 工事・工程選択画面
│   ├── camera/         # CameraX カメラ画面
│   ├── recognize/      # 画像選択・OCR 実行画面
│   ├── confirm/        # 認識結果確認・登録画面
│   ├── register/       # 登録完了画面
│   └── library/        # 登録履歴一覧画面
└── CrossVisionApp.kt   # Application クラス
```

---

## ブランチ運用ルール

```
prototype ← 動作確認済みの統合ブランチ（このブランチ）
develop   ← OCR エンジン基盤
feature/* ← 機能ごとの作業ブランチ
```

### 開発フロー

```bash
# 1. prototype から作業ブランチを切る
git checkout prototype
git pull origin prototype
git checkout -b feature/機能名

# 2. 作業してコミット
git add .
git commit -m "feat: 機能の説明"

# 3. prototype へ Pull Request を出す
git push origin feature/機能名
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

## 関連リソース

- [画像処理デモ（スタンドアロン）](./image-preprocessing/) - OCR 単体の動作確認用
- 正解ラベルデータ・テスト画像 → チーム共有の Google Drive を参照
