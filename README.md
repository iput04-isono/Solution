# 鉄骨文字認識アプリ - GroupA

鉄骨工程管理システムのAndroid アプリケーションです。
手書きの製品番号をカメラで撮影し、複数まとめて工程登録できるシステムになります。

---

## 開発環境のセットアップ

### 必要なもの

| ツール | バージョン |
|--------|-----------|
| Android Studio | Meerkat 以降推奨 |
| JDK | 11 以上 |
| Kotlin | 2.0.21 |
| Android SDK | API 36（compileSdk） |
| 最低動作 SDK | API 24（Android 7.0） |

### 手順

**1. リポジトリをクローン**

```bash
git clone https://github.com/iput04-isono/Solution.git
cd Solution/mainproject
```

**2. Android Studio で開く**

`File → Open` から `Solution/mainproject` フォルダを選択します。

**3. Gradle Sync**

Android Studio が自動で Sync を促してくるので `Sync Now` を押してください。
促されない場合は `File → Sync Project with Gradle Files` を実行してください。

**4. ビルドして実行**

エミュレーターまたは実機を接続して、上部の ▶ ボタンを押してください。

---

## 使用ライブラリ

| ライブラリ | バージョン | 用途 |
|-----------|-----------|------|
| androidx.core-ktx | 1.17.0 | Kotlin 拡張 |
| androidx.appcompat | 1.6.1 | 後方互換 UI |
| material | 1.10.0 | マテリアルデザイン |
| androidx.activity | 1.12.2 | Activity 拡張 |
| androidx.constraintlayout | 2.1.4 | レイアウト |
| Room Runtime | 2.6.1 | ローカル DB |
| Room KTX | 2.6.1 | Room Coroutines 対応 |
| KSP | 2.0.21-1.0.28 | コード生成（Room用） |

---

## プロジェクト構成

```
app/src/main/java/com/example/mainproject/
├── data/
│   ├── db/
│   │   ├── entity/   # Roomテーブル定義
│   │   ├── dao/      # SQLクエリ定義
│   │   └── AppDatabase.kt
│   ├── api/          # Retrofit通信（今後実装）
│   ├── repository/   # DB↔UIの橋渡し（今後実装）
│   └── model/        # APIモデル（今後実装）
├── ui/
│   ├── login/        # ログイン画面（今後実装）
│   ├── recognize/    # 認識・確認画面（今後実装）
│   └── register/     # 登録画面（今後実装）
├── worker/           # WorkManager 自動同期（今後実装）
├── ocr/              # 画像処理・OCR（今後実装）
├── auth/             # JWT認証（今後実装）
└── util/             # 共通ユーティリティ（今後実装）
```

---

## ブランチ運用ルール

```
main      ← 最終リリース用（直接pushしない）
demo      ← デモ用スナップショット（直接pushしない）
develop   ← 普段の開発・統合ブランチ
feature/* ← 機能ごとの作業ブランチ
fix/*     ← バグ修正ブランチ
```

### 開発フロー

```bash
# 1. developから作業ブランチを切る
git checkout develop
git pull origin develop
git checkout -b feature/機能名

# 2. 作業してコミット
git add .
git commit -m "feat: 機能の説明"

# 3. developへPull Requestを出す
git push origin feature/機能名
```

### コミットメッセージのルール

| プレフィックス | 用途 |
|--------------|------|
| `feat:` | 新機能追加 |
| `fix:` | バグ修正 |
| `docs:` | ドキュメント変更 |
| `refactor:` | リファクタリング |
| `test:` | テスト追加 |
| `chore:` | Gradle・設定変更 |

---

## ビルド方法

```bash
# デバッグビルド
./gradlew assembleDebug

# リリースビルド
./gradlew assembleRelease

# テスト実行
./gradlew test
```

---

## 関連ドキュメント

- システム設計書 v1.1（チームA）
- [画像処理デモ](../image-preprocessing/)（demo ブランチ参照）
