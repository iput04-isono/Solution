## 概要 / Summary

メインアプリに PaddleOCR ベースの文字認識機能を統合しました。
以前マージされた `feature/ocr-engine-improvement`・`feature/label-matcher` を UI まで繋ぎ込み、実際に撮影した画像で鉄骨製品コードを認識・登録できる状態にしています。

**元のアプリ（UI のみ）はそのまま残っており、本 PR は OCR 機能を上乗せするものです。**
**スタンドアロンの検証デモ（`image-preprocessing/`）は PR #7 として別途公開しています。**

---

## 変更ファイル一覧

| ファイル | 変更種別 | 内容 |
|---|---|---|
| `ocr/LabelMatcher.kt` | 新規追加 | 正解ラベルとの Levenshtein 照合（閾値 3 以内） |
| `assets/product_labels.txt` | 新規追加 | 正解ラベルマスター 1017 件 |
| `ocr/OcrEngine.kt` | 改修 | 多角形検出パラメータ調整・認識領域オーバーレイ描画 |
| `ocr/OcrProcessor.kt` | 改修 | 全結果を距離≤3（登録候補）と距離>3（参考）に分類 |
| `ocr/ImagePreprocessor.kt` | 改修 | 最大リサイズ幅 960→1280（カメラ画像の精度向上） |
| `ui/camera/CameraActivity.kt` | 改修 | `setTargetRotation` 追加（撮影向き EXIF 正常化） |
| `ui/recognize/RecognizeActivity.kt` | 改修 | EXIF 回転補正・オーバーレイ画像保存・Intent 振り分け |
| `ui/confirm/ConfirmActivity.kt` | 改修 | 認識領域画像表示・距離>3 の結果を下部別枠表示 |
| `res/layout/activity_confirm.xml` | 改修 | NestedScrollView 化・マッチなし別枠レイアウト追加 |

---

## 文字認識処理の仕組み

```
撮影・選択した画像
    |
    v
ImagePreprocessor（リサイズ：長辺 1280px 以内）
    |
    v
OcrEngine --- 検出（DBNet）
    |              長辺 640px にリサイズした画像を ONNX 推論
    |              二値化マップから BFS で連結成分を検出
    |              PCA で最小外接矩形（多角形）を算出
    |              UNCLIP_RATIO=2.0 で領域を拡張
    |
    +--- 認識（SVTR）
    |       0度/180度の両方向で認識
    |       LabelMatcher の編集距離が小さい向きの結果を採用
    |
    +--- オーバーレイ画像生成
            緑（信頼度≥0.7）/ 黄（≥0.4）/ 赤（<0.4）で枠を描画
    |
    v
LabelMatcher（Levenshtein 距離 <= 3）
    +- 距離 <= 3 → 登録候補として上部リストに表示（正解ラベルで表示）
    +- 距離 >  3 → 下部「参考」枠に OCR 生テキストと信頼度を表示
    |
    v
ConfirmActivity
    +- 認識領域画像（どこを読んだか視覚確認）
    +- 登録候補リスト（チェックボックスで選択して「登録」）
    +- 参考欄（マッチしなかった認識結果、登録には使われない）
```

### 主なパラメータ

| パラメータ | 値 | 役割 |
|---|---|---|
| `DET_THRESHOLD` | 0.28 | テキスト検出の閾値（低いほど多く検出） |
| `UNCLIP_RATIO` | 2.0 | 検出領域の拡張率 |
| `MAX_REGIONS` | 12 | 1 枚から取り出す最大テキスト領域数 |
| `MAX_EDIT_DISTANCE` | 3 | ラベル照合の最大編集距離 |
| `MAX_SIZE` | 1280 | 前処理リサイズの最大長辺 px |

---

## 実機テスト手順（チームメンバー向け）

### 必要なもの

- Android 実機（Android 8.0 以上）
- Android Studio（SDK + USB ドライバインストール済み）
  - Windows の場合は [Samsung USB Driver](https://developer.samsung.com/mobile/android-usb-driver.html) も必要

### 手順

**1. リポジトリを取得**

```bash
git clone https://github.com/iput04-isono/Solution.git
cd Solution
git checkout develop
```

> このPRがマージされた後は上記コマンドのみでOKです。
> マージ前に試す場合は `git checkout feature/label-matcher-integration` を使ってください。

**2. ログイン情報**

| ID | パスワード | 権限 |
|---|---|---|
| `admin` | `admin123` | 管理者 |
| `user01` | `pass01` | 作業員 |
| `user02` | `pass02` | 作業員 |

**3. ビルド・インストール**

```bash
# USB でスマートフォンを接続し、開発者オプション + USB デバッグを有効にした上で：
./gradlew installDebug   # Mac/Linux
.\gradlew.bat installDebug   # Windows (PowerShell)
```

または Android Studio で「Run」ボタンを押す。

**4. テスト手順**

1. アプリ起動 → ログイン
2. 工事・工程を選択
3. カメラアイコン → 撮影（またはギャラリーから選択）
4. 認識結果確認画面で確認：
   - **上部画像**：認識した領域が色付き枠で表示
   - **中部リスト**：編集距離 ≤ 3 の正解ラベル候補（チェックして「登録」）
   - **下部黄色枠**：マッチしなかった認識結果（参考用）

**5. OCR モデルファイルについて**

`app/src/main/assets/` に以下が含まれています（追加作業不要）：

- `det.onnx`：テキスト検出モデル（DBNet、PP-OCRv4 mobile）
- `ppocr_rec.onnx`：文字認識モデル（SVTR、PP-OCRv4 mobile）
- `dict.txt`：文字辞書
- `product_labels.txt`：正解ラベルマスター 1017 件

---

## 改善された点（元アプリとの比較）

| 項目 | 元アプリ（UI のみ） | 本 PR 後 |
|---|---|---|
| 文字認識 | なし | PaddleOCR（DBNet + SVTR）で動作 |
| 撮影画像の回転 | 90°ずれることがあった | EXIF 補正で撮影向きのまま認識 |
| 認識精度 | − | ラベル照合で誤認識を自動補正 |
| 結果の視覚確認 | なし | 認識領域をカラー枠で画像上に表示 |
| 参考情報の表示 | なし | マッチしなかった認識結果も別枠で表示 |

---

## 関連 PR / Related PRs

- #4 `feature/ocr-engine-improvement` — OCR エンジン基盤（マージ済み）
- #5 `feature/label-matcher` — LabelMatcher 基盤（マージ済み）
- #7 `feature/update-preprocessing-demo` — スタンドアロン検証デモ（別アプリ・別 PR）
