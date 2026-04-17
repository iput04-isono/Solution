# 画像処理 OCR 検証デモ

> **注記**: これは鉄骨文字認識アプリの**画像処理班（坂井）による単独検証用デモアプリ**です。  
> 最終成果物（鉄骨文字認識アプリ本体）とは別物です。  
> チームのメインアプリ（`app/`）への組み込みコードは `app/src/main/java/.../ocr/` を参照してください。

---

## このデモの目的

- **前処理 + OCR認識 + ラベル照合**の一連のパイプラインを実機で単独検証する
- 新旧アルゴリズムの認識精度を並べて比較する
- 認識結果と正解ラベルの照合精度を目視確認する

---

## システム全体における位置づけ

```
【鉄骨文字認識アプリ 全体フロー】

  撮影
    │
    ▼
┌─────────────────────────────┐
│  Step 1: 画像前処理           │
│  （リサイズ・CLAHE・ノイズ除去）  │ ← このデモの開発範囲（坂井）
└─────────────────────────────┘
    │  前処理済み画像
    ▼
┌─────────────────────────────┐
│  Step 2: 文字領域検出 + 認識  │ ← このデモで検証済み
│  （PaddleOCR / DBNet+SVTR） │    メインアプリの ocr/ に統合済み
└─────────────────────────────┘
    │
    ▼
┌─────────────────────────────┐
│  Step 3: 正解ラベル照合       │ ← このデモで検証済み
│  （Levenshtein 編集距離 ≤3） │    メインアプリの ocr/ に統合済み
└─────────────────────────────┘
    │
    ▼
  認識結果の表示・工程登録（メインアプリ担当）
```

---

## デモアプリの機能

| 画面 | 内容 |
|---|---|
| **MainActivity** | ギャラリーから画像を選択してOCR実行。認識結果とラベル候補を表示 |
| **CompareActivity** | 元画像 vs 前処理後の OCR 結果を左右に並べて比較 |
| **AlgoCompareActivity** | 旧アルゴリズム vs 新アルゴリズムの認識精度を並べて比較 |
| **BatchResultsActivity** | テスト画像を一括処理して認識精度サマリーを表示 |

---

## 使用技術

| 技術 | 内容 |
|---|---|
| PaddleOCR PP-OCRv4 | テキスト検出（DBNet）+ 文字認識（SVTR） |
| ONNX Runtime 1.17.1 | オンデバイス推論（NNAPI対応） |
| Levenshtein 編集距離 | 正解ラベル照合（編集距離 ≤3 の候補を最大3件） |
| Android 標準API のみ | OpenCV 不使用（外部前処理はPaddleOCR精度を低下させるため） |

---

## 必要な assets

| ファイル | 役割 | 入手方法 |
|---|---|---|
| `det.onnx`（84MB） | テキスト領域検出モデル（DBNet） | このリポジトリに含まれる |
| `ppocr_rec.onnx`（7.5MB） | 文字認識モデル（SVTR） | このリポジトリに含まれる |
| `dict.txt` | 認識文字辞書 | このリポジトリに含まれる |
| `product_labels.txt` | 正解製品コード一覧（1,017件） | このリポジトリに含まれる |
| `images/` | テスト用鉄骨画像（14枚） | **Google Drive から取得**（下記参照） |
| `Images_Answer/` | テスト画像ごとの正解ラベル | **Google Drive から取得**（下記参照） |

### テストデータの配置手順

`images/` と `Images_Answer/` はチームの Google Drive で共有しています。  
以下の手順でローカルに配置してください。

```
1. Google Drive の共有フォルダから
   「images/」と「Images_Answer/」をダウンロード

2. 以下のパスに配置する
   image-preprocessing/
   └── app/src/main/assets/
       ├── images/          ← ここに画像ファイルを配置
       └── Images_Answer/   ← ここに正解ラベルを配置
```

> ※ Google Drive のリンクは坂井まで Slack でお問い合わせください。

---

## 起動方法

### Android 実機で起動する

```bash
# 1. 実機をUSBで接続・USBデバッグを有効化
adb devices   # デバイスが表示されることを確認

# 2. ビルド＆インストール
cd image-preprocessing
.\gradlew.bat installDebug

# 3. 起動
adb shell am start -n com.example.imagepreprocessingtest/.MainActivity
```

### Android Studio から起動する

```
File → Open → image-preprocessing/ フォルダを選択
→ ▶ Run ボタンを押す
```

---

## 検証結果（実機テスト: Galaxy S20 Ultra / Android 13）

| 項目 | 結果 |
|---|---|
| OCR処理時間（平均） | 約 1,100ms / 枚 |
| 要件（3秒以内） | ✅ 達成 |
| 正面・均一照明 | ✅ ラベル完全一致（編集距離=0）多数 |
| 斜め撮影 | ✅ PCA多角形検出で正常クロップ |
| 逆さま文字 | ✅ 正逆両方認識して距離の近い方を採用 |

---

## ビルド環境

| ツール | バージョン |
|---|---|
| Android Studio | Meerkat 以降 |
| Kotlin | 2.0.21 |
| Android SDK | API 36（compileSdk）/ API 24（minSdk） |
| ONNX Runtime | 1.17.1 |
| Gradle | 9.2.0 |

---

## 担当

**坂井壱謙**（画像処理班）  
メインアプリへの統合コード: [`app/src/main/java/.../ocr/`](../app/src/main/java/com/example/mainproject/ocr/)
