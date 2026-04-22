## 概要

実機動作確認済みのフルアプリ（`com.crossvision.f`）を復元します。

`main` ブランチに存在していたアプリのコードが失われたため、
`feature/label-matcher-integration` ブランチに保存されていた最終動作版をそのまま `develop` に追加します。

旧スタブコード（`com.example.mainproject`）は削除し、完全な実装に置き換えています。

---

## 含まれる機能一覧

| 機能 | クラス |
|---|---|
| ログイン・認証 | `LoginActivity` / `LoginViewModel` |
| 工事・工程選択 | `ProcessSelectionActivity` / `ProcessSelectionViewModel` |
| カメラ撮影（CameraX） | `CameraActivity` |
| ギャラリー選択・OCR実行 | `RecognizeActivity` |
| 文字認識エンジン（PaddleOCR） | `OcrEngine`（DBNet + SVTR） |
| ラベル照合 | `LabelMatcher`（Levenshtein 距離 ≤ 3） |
| 認識結果確認・オーバーレイ表示 | `ConfirmActivity` |
| 登録候補・参考情報の別枠表示 | `ConfirmActivity`（距離 > 3 を下部に分離） |
| DB 保存 | `AppRepository` / Room Database |
| 登録履歴一覧 | `LibraryActivity` |
| サーバー同期 | `SyncWorker` |

---

## 変更内容

### 追加したファイル（`com.crossvision.f`）
- `data/local/` - Room DB（User / Construction / Process / Registration）
- `data/model/` - データモデル
- `data/repository/AppRepository.kt`
- `ocr/OcrEngine.kt` - 多角形検出パラメータ最適化済み
- `ocr/LabelMatcher.kt` - 編集距離照合
- `ocr/OcrProcessor.kt` - 登録候補 / 参考情報に自動分類
- `ocr/ImagePreprocessor.kt` - MAX_SIZE=1280
- `ocr/ProductCodeValidator.kt` - フォーマット検証
- `ui/login/` `ui/process/` `ui/camera/` `ui/recognize/` `ui/confirm/` `ui/register/` `ui/library/`
- `sync/SyncWorker.kt` / `SyncManager.kt`
- レイアウト XML 全画面分
- `assets/product_labels.txt` - 正解ラベル 1017 件

### 削除したファイル（旧スタブ）
- `com.example.mainproject.MainActivity`
- `com.example.mainproject.data.*`（Room スタブ）
- `com.example.mainproject.ui.*`（`.gitkeep` のみのスタブ）

---

## ビルド・実機テスト手順

### ビルド方法

```bash
git clone https://github.com/iput04-isono/Solution.git
cd Solution
git checkout feature/restore-full-app   # マージ前
# または git checkout develop           # マージ後

gradlew.bat installDebug   # Windows
./gradlew installDebug     # Mac/Linux
```

### ログイン情報

| ID | パスワード | 権限 |
|---|---|---|
| `admin` | `admin123` | 管理者 |
| `user01` | `pass01` | 作業員 |
| `user02` | `pass02` | 作業員 |

### OCR モデルファイル

`app/src/main/assets/` に以下が含まれています（追加作業不要）。

```
det.onnx          テキスト検出モデル
ppocr_rec.onnx    文字認識モデル
dict.txt          文字辞書
product_labels.txt 正解ラベル 1017 件
```

### 認識結果確認画面の見方

1. **認識領域オーバーレイ**：どの文字を認識したか画像で確認（緑=高信頼度・赤=低信頼度）
2. **登録候補リスト**：ラベル距離 ≤ 3 のもの（チェックして登録）
3. **参考情報（黄色枠）**：ラベル距離 > 3 のもの（登録されない・確認用）

---

## 備考

このPRは **コードの復元**が目的です。
OCR 精度改善の詳細については PR #8 を参照してください。
