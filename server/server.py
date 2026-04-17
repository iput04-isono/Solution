"""
鉄骨文字認識アプリ - 開発用ローカルサーバー
起動方法: uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""

from fastapi import FastAPI
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
from typing import List
from datetime import datetime
import json, os

app = FastAPI(title="鉄骨認識サーバー（開発用）")

# ── リクエスト/レスポンス モデル ──────────────────────────

class RegistrationRequest(BaseModel):
    process_id: int
    division: str          # "start" or "end"
    worker_id: int
    device_id: str
    registered_at: str     # ISO8601 文字列
    product_numbers: List[str]

class RegistrationResponse(BaseModel):
    success: bool
    message: str | None = None

# ── データ保存先（JSON ファイル） ──────────────────────────

DATA_FILE = os.path.join(os.path.dirname(__file__), "registrations.json")

def load_data() -> list:
    if not os.path.exists(DATA_FILE):
        return []
    with open(DATA_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

def save_data(data: list):
    with open(DATA_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

# ── エンドポイント ──────────────────────────────────────

@app.post("/api/registrations", response_model=RegistrationResponse)
def post_registration(req: RegistrationRequest):
    data = load_data()
    entry = {
        "id": len(data) + 1,
        "process_id": req.process_id,
        "division": req.division,
        "worker_id": req.worker_id,
        "device_id": req.device_id,
        "registered_at": req.registered_at,
        "product_numbers": req.product_numbers,
        "received_at": datetime.now().isoformat(),
    }
    data.append(entry)
    save_data(data)
    print(f"[受信 {entry['received_at']}] 区分={entry['division']} 製品番号={entry['product_numbers']}")
    return RegistrationResponse(success=True, message=f"{len(req.product_numbers)}件を保存しました")

@app.get("/api/registrations")
def get_registrations():
    """保存済みデータ確認用（JSON）"""
    return load_data()

@app.get("/health")
def health():
    return {"status": "ok"}

@app.get("/", response_class=HTMLResponse)
def view_registrations():
    """同期済みデータをブラウザで確認するページ"""
    data = load_data()

    division_label = {"start": "入庫", "end": "出庫"}

    rows = ""
    for entry in reversed(data):  # 新しい順に表示
        numbers_html = "".join(
            f'<span class="badge">{n}</span>' for n in entry.get("product_numbers", [])
        )
        div = division_label.get(entry.get("division", ""), entry.get("division", ""))
        received = entry.get("received_at", "")[:19].replace("T", " ")
        rows += f"""
        <tr>
            <td>{entry.get('id')}</td>
            <td><span class="div-{'in' if entry.get('division')=='start' else 'out'}">{div}</span></td>
            <td>{numbers_html}</td>
            <td>{received}</td>
            <td>{entry.get('device_id', '')}</td>
        </tr>"""

    count = len(data)
    html = f"""<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta http-equiv="refresh" content="5">
<title>鉄骨認識 - 同期データ一覧</title>
<style>
  body {{ font-family: sans-serif; padding: 20px; background: #f5f5f5; }}
  h1 {{ color: #333; }}
  .summary {{ background: #fff; padding: 12px 20px; border-radius: 8px; margin-bottom: 16px;
              display: inline-block; box-shadow: 0 1px 4px rgba(0,0,0,.1); }}
  table {{ width: 100%; border-collapse: collapse; background: #fff;
           box-shadow: 0 1px 4px rgba(0,0,0,.1); border-radius: 8px; overflow: hidden; }}
  th {{ background: #4a90d9; color: #fff; padding: 10px 14px; text-align: left; }}
  td {{ padding: 10px 14px; border-bottom: 1px solid #eee; vertical-align: top; }}
  tr:last-child td {{ border-bottom: none; }}
  tr:hover td {{ background: #f0f7ff; }}
  .badge {{ display: inline-block; background: #e8f0fe; color: #1a73e8;
            border-radius: 4px; padding: 2px 8px; margin: 2px; font-size: 13px; }}
  .div-in  {{ color: #0a7a0a; font-weight: bold; }}
  .div-out {{ color: #c0392b; font-weight: bold; }}
  .note {{ color: #888; font-size: 12px; margin-top: 8px; }}
</style>
</head>
<body>
<h1>📋 同期データ一覧</h1>
<div class="summary">合計 <strong>{count}</strong> 件　<span class="note">（5秒ごとに自動更新）</span></div>
<table>
  <thead>
    <tr><th>ID</th><th>区分</th><th>製品番号</th><th>受信日時</th><th>デバイスID</th></tr>
  </thead>
  <tbody>
    {'<tr><td colspan="5" style="text-align:center;color:#aaa">データなし</td></tr>' if not data else rows}
  </tbody>
</table>
</body>
</html>"""
    return html