## 概要

`prototype` ブランチのメインアプリに、PaddleOCR の精度改善・認識領域の視覚的確認・ラベル照合強化を実装しました。

既存の `OcrEngine` / `LabelMatcher` をベースに拡張しており、`MainActivity` の基本的な使い方はそのままで機能を追加しています。

---

## 変更ファイル

| ファイル | 種別 | 変更内容 |
|---|---|---|
| `ocr/OcrEngine.kt` | 改修 | 検出パラメータ調整・オーバーレイ描画・LabelMatcher を使った向き選択 |
| `ocr/OcrProcessor.kt` | 新規追加 | 登録候補（距離<=3）と参考情報（>3）に自動分類するオーケストレータ |
| `assets/product_labels.txt` | 追加 | 正解ラベルマスター 1017 件 |
| `MainActivity.kt` | 改修 | EXIF 回転補正・多角形 OCR・オーバーレイ表示・マッチなし別枠 |
| `res/layout/activity_main.xml` | 改修 | 認識領域画像表示枠・マッチなし参考枠を追加 |
| `app/build.gradle.kts` | 改修 | androidx.exifinterface 依存関係を追加 |
| `gradle/libs.versions.toml` | 改修 | exifinterface バージョン定義を追加 |

---

## 改善内容と理由

### 1. 多角形検出パラメータの最適化

カメラ撮影画像ではギャラリー画像より文字が小さく映るため、以下を調整しました。

| パラメータ | 変更前 | 変更後 | 効果 |
|---|---|---|---|
| `DET_THRESHOLD` | 0.38 | 0.28 | より薄いインク・反射光の文字も検出 |
| `BFS_MIN_PX` | 60 | 25 | 小さな文字領域も拾う |
| `MIN_POLY_AREA` | 300 | 100 | 小さな多角形を除外しない |
| `UNCLIP_RATIO` | 1.5 | 2.0 | 領域を広めに切り出す |
| `MAX_REGIONS` | 8 | 12 | 1 枚あたり最大 12 領域まで処理 |

### 2. 認識領域のオーバーレイ表示

`OcrEngine.runOcrPolygonWithOverlay()` を新規追加。どの文字を認識したか画像上で視覚確認できます。

- 緑：信頼度 >= 70%
- 黄：信頼度 >= 40%
- 赤：信頼度 < 40%

### 3. LabelMatcher を使った向き選択

正向き・逆向きの両方を認識し、**ラベル距離が小さい方**を採用します（従来は信頼度のみ）。倒立した文字が正しく認識されるようになります。

### 4. OcrProcessor による結果分類

新規クラス `OcrProcessor` が以下を自動分類します。

- `matched`：正解ラベルとの編集距離 <= 3 → 登録候補として表示
- `unmatched`：距離 > 3 → 下部黄色枠で参考表示（登録されない）

### 5. カメラ撮影画像の EXIF 回転補正

`androidx.exifinterface.media.ExifInterface` を使い、撮影向きに合わせて Bitmap を正しく回転してから OCR に渡します。

---

## 画面の変化

```
[撮影 / ギャラリー選択]
        |
        v
[元画像プレビュー]
[認識領域オーバーレイ画像 --- 緑/黄/赤の枠で領域を確認]
        |
        v
【登録候補】               ← 距離 <= 3（正解ラベルを表示）
  ✓ B1Sb30N-7A  (距離:1, 信頼度:82%)
  ✓ H150x150x7  (完全一致, 信頼度:91%)

[DBに保存] ボタン

参考: 正解ラベルと一致しなかった認識結果  ← 距離 > 3（参考のみ）
  ・AB3CX  (信頼度:45%)
```

---

## 実機テスト手順

### 必要なもの
- Android 実機（Android 8.0 以上）
- Android Studio または ADB（USB デバッグ有効）
- Windows の場合は [Samsung USB Driver](https://developer.samsung.com/mobile/android-usb-driver.html)

### 手順

```bash
git clone https://github.com/iput04-isono/Solution.git
cd Solution
git checkout feature/ocr-ui-improvements
.\gradlew.bat installDebug   # Windows
./gradlew installDebug       # Mac/Linux
```

> このPRがマージされれば `git checkout prototype` だけでOKです。

### assets に必要なファイル（リポジトリに含まれています）

```
app/src/main/assets/
  det.onnx          テキスト検出モデル（PP-OCRv4 DBNet）
  ppocr_rec.onnx    文字認識モデル（PP-OCRv4 SVTR）
  dict.txt          文字辞書
  product_labels.txt 正解ラベルマスター 1017 件
```

---

## 関連ブランチ

- `prototype`：このPRのマージ先（OCRエンジン＋同期処理が統合済み）
- `develop`：OCRエンジン基盤（PR #4, #5）
