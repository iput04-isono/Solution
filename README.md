# Cross Vision F

**鉄骨工程管理 Android アプリケーション & PC 管理システム**

Cross Vision F は、鉄骨の工程管理を効率化するためのトータルソリューションです。
スマートフォンのカメラで鉄骨に手書きされた製品番号を撮影し、PaddleOCR (ONNX Runtime) による高速・高精度なオンデバイス認識を行います。
認識したデータは、同一 WiFi 内の PC サーバーへ**自動的に発見・接続**して同期され、一瞬で PC 側での管理・CSV 出力が可能になります。

---

### 📸 認識精度の確認と候補選択 (Candidate Support)
認識精度のさらなる向上と現場での修正コスト削減のため、以下の機能を搭載。
- **画像プレビュー**: 確認画面上部に撮影画像を常時表示。文字と実物を即座に突き合わせ可能。
- **候補選択 (N-best candidates)**: AIが認識に迷った箇所に対して、信頼度順に複数の候補文字を提示。製品コードをタップするだけで候補リストが表示され、ワンタップで修正が完了します。

### 📡 サーバー自動発見機能 (ZeroTouch Sync)
**mDNS (Multicast DNS)** を活用し、スマホがネットワーク上の PC サーバーを自動的に探し出します。
- **設定不要**: PC の IP アドレスが変更されても、アプリ側の設定を書き換える必要はありません。
- **自動接続**: 同一ネットワーク (WiFi) に入るだけで、同期準備が整います。

### 🛡️ セキュリティと認証 (API Key Auth)
ランダム生成された API キーによる認証を導入し、同一ネットワーク内での安全な同期を実現。

### 📋 オフライン完全対応 ＆ 現場最適化 UI
- **オフライン保存**: 電波の届かない現場内でも Room Database にデータを一旦保存。
- **現場ファーストなUI**: 不要な入力項目を排除し、手袋をしたままでも操作しやすい「再読み込み」「ログアウト」ボタンを配置。設計書 ver2.0 に準拠したレイアウト。

---

## システム構成

```mermaid
graph TD
    subgraph "現場 (Android)"
        App[Cross Vision F App]
        OCR[PaddleOCR/ONNX]
        Room[(Room DB)]
        NSD[Network Service Discovery]
    end
    
    subgraph "事務所 (Local PC)"
        Server[Flask Server]
        Admin[Web Admin UI]
        SQLite[(SQLite DB)]
        ZC[Zeroconf Broadcast]
    end

    App -- "X-API-KEY 認証" --> Server
    NSD -- "サーバーを自動発見" --- ZC
    OCR -- "認識結果" --> App
    App -- "オフライン保存" --> Room
    Server -- "表示/管理" --> Admin
    Server -- "データ保存" --> SQLite
```

---

## 技術スタック

### Android アプリ
- **Kotlin** / **MVVM Architecture** / **Jetpack ViewBinding**
- **PaddleOCR (ONNX Runtime)**: 高精度オンデバイス文字認識
- **Retrofit2 / OkHttp3**: 認証ヘッダー付き HTTP クライアント
- **NsdManager**: ネットワークサービス自動発見
- **Room Database**: ローカルデータ永続化
- **WorkManager**: バックグラウンド同期タスク管理

### PC 管理サーバー
- **Python 3.x** / **Flask**: Web API サーバー
- **Zeroconf**: mDNS によるサービス情報配信
- **Flask-SQLAlchemy**: データベース ORM
- **SQLite3**: ローカル DB

---

## セットアップ

### 1. PC サーバー側 (Python / Flask)

#### ① 必要なライブラリのインストール (初回のみ)
```bash
cd server
pip install -r requirements.txt
```

#### ② サーバーの起動
```bash
python app.py
```
起動すると、`[*] 自動発見サービスを開始` というログが表示され、スマホからの接続待ち状態になります。

### 2. Android アプリ側

1. Android Studio でプロジェクトを開き、実機またはエミュレーターで起動します。
2. PC とスマホが同じ WiFi に接続されていることを確認します。
3. アプリ内の「同期」を実行すると、サーバーを自動で見つけ出し、未送信データを転送します。

---

## 今後の予定

- [x] OCR エンジンの PaddleOCR (ONNX) への換装
- [x] Flask サーバーによる実 API 同期の実装
- [x] PC 管理画面の構築と CSV 出力機能の実装
- [x] サーバーの自動発見機能 (mDNS) の実装
- [x] API キーによる認証機能の導入
- [x] OCR で読み取った際のエビデンス写真のサーバー転送（プレビュー機能）
- [x] OCR 候補選択機能 (N-best candidates) の実装
- [ ] 工事・工程マスターデータのサーバー同期機能

---

## ライセンス / メンバー

本プロジェクトは社内開発用です。

**A Group**
- 曽我部 竹知世, 小野 航平, 上田 瑞樹, 礒野 虎太, 相良 謙介
