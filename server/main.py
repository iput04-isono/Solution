"""
鉄骨文字認識アプリ - 開発用ローカルサーバー
起動方法: uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""

from fastapi import FastAPI, Response, Security, HTTPException, Depends
from fastapi.security.api_key import APIKeyHeader
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
from typing import List, Optional
from datetime import datetime
import json, os, csv, io, socket, starlette.status
from zeroconf import IPVersion, ServiceInfo, Zeroconf

app = FastAPI(title="鉄骨認識サーバー（開発用）")

BASE_DIR = os.path.dirname(__file__)

# ── モデル ──────────────────────────────────────────────

class RegistrationRequest(BaseModel):
    process_id: int
    division: str
    worker_id: int
    device_id: str
    registered_at: str
    product_numbers: List[str]
    construction_name: Optional[str] = None
    process_name: Optional[str] = None

class RegistrationResponse(BaseModel):
    success: bool
    message: str | None = None

class BulkDeleteRequest(BaseModel):
    ids: List[int]

# ── セキュリティ ─────────────────────────────────────────

API_KEY = "cvf_7s_9922_zrkp_8x11"
API_KEY_NAME = "X-API-KEY"
api_key_header = APIKeyHeader(name=API_KEY_NAME, auto_error=False)

async def get_api_key(header_key: str = Depends(api_key_header)):
    if header_key == API_KEY:
        return header_key
    raise HTTPException(
        status_code=starlette.status.HTTP_401_UNAUTHORIZED,
        detail="Invalid API Key"
    )

# ── データ保存 ──────────────────────────────────────────

DATA_FILE = os.path.join(BASE_DIR, "registrations.json")

def load_data() -> list:
    if not os.path.exists(DATA_FILE):
        return []
    with open(DATA_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

def save_data(data: list):
    with open(DATA_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

# ── API ─────────────────────────────────────────────────

@app.post("/api/registrations", response_model=RegistrationResponse)
def post_registration(req: RegistrationRequest, api_key: str = Depends(get_api_key)):
    data = load_data()
    entry = {
        "id": len(data) + 1,
        "process_id": req.process_id,
        "division": req.division,
        "worker_id": req.worker_id,
        "device_id": req.device_id,
        "registered_at": req.registered_at,
        "product_numbers": req.product_numbers,
        "construction_name": req.construction_name,
        "process_name": req.process_name,
        "received_at": datetime.now().isoformat(),
    }
    data.append(entry)
    save_data(data)
    print(f"[受信 {entry['received_at']}] 区分={entry['division']} 製品番号={entry['product_numbers']}")
    return RegistrationResponse(success=True, message=f"{len(req.product_numbers)}件を保存しました")

@app.get("/api/registrations")
def get_registrations():
    return load_data()

@app.delete("/api/registrations/{entry_id}", response_model=RegistrationResponse)
def delete_registration(entry_id: int, api_key: str = Depends(get_api_key)):
    """単一データを削除する"""
    data = load_data()
    new_data = [d for d in data if d.get("id") != entry_id]
    if len(new_data) == len(data):
        raise HTTPException(status_code=404, detail=f"ID {entry_id} が見つかりません")
    save_data(new_data)
    print(f"[削除] ID={entry_id}")
    return RegistrationResponse(success=True, message=f"ID {entry_id} を削除しました")

@app.delete("/api/registrations", response_model=RegistrationResponse)
def bulk_delete_registrations(req: BulkDeleteRequest, api_key: str = Depends(get_api_key)):
    """複数データを一括削除する"""
    if not req.ids:
        raise HTTPException(status_code=400, detail="削除対象のIDが指定されていません")
    data = load_data()
    id_set = set(req.ids)
    new_data = [d for d in data if d.get("id") not in id_set]
    deleted_count = len(data) - len(new_data)
    save_data(new_data)
    print(f"[一括削除] {deleted_count}件削除 IDs={sorted(id_set)}")
    return RegistrationResponse(success=True, message=f"{deleted_count}件を削除しました")

@app.get("/health")
def health():
    return {"status": "ok"}

@app.get("/api/export/csv")
def export_csv():
    data = load_data()
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["ID", "工事名", "工程名", "製品番号", "受信日時", "デバイスID"])
    for entry in data:
        const_name = entry.get("construction_name") or "—"
        proc_name = entry.get("process_name") or "—"
        numbers = ", ".join(entry.get("product_numbers", []))
        received = entry.get("received_at", "")[:19].replace("T", " ")
        writer.writerow([entry.get("id"), const_name, proc_name, numbers, received, entry.get("device_id", "")])
    csv_bytes = ("\ufeff" + output.getvalue()).encode("utf-8")
    return Response(
        content=csv_bytes,
        media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=registrations.csv"}
    )

@app.get("/", response_class=HTMLResponse)
@app.get("/admin", response_class=HTMLResponse)
def dashboard():
    """管理画面のHTMLをファイルから読み込んで返す"""
    file_path = os.path.join(BASE_DIR, "dashboard.html")
    if os.path.exists(file_path):
        with open(file_path, "r", encoding="utf-8") as f:
            return f.read()
    return "dashboard.html not found"
# ── 自動発見 (mDNS/Zeroconf) 配信 ────────────────────────

class DiscoveryServer:
    def __init__(self, port):
        self.zeroconf = Zeroconf(ip_version=IPVersion.V4Only)
        self.port = port
        self.service_info = None

    def start(self):
        try:
            hostname = socket.gethostname()
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.connect(("8.8.8.8", 80))
                local_ip = s.getsockname()[0]
                s.close()
            except Exception:
                local_ip = socket.gethostbyname(hostname)

            # サービス名: SevenStarServer (Androidアプリ側がこの名前を探します)
            desc = {"version": "1.0", "name": "CrossVision-F-Server"}
            self.service_info = ServiceInfo(
                "_crossvision._tcp.local.",
                f"SevenStarServer.{hostname}._crossvision._tcp.local.",
                addresses=[socket.inet_aton(local_ip)],
                port=self.port,
                properties=desc,
                server=f"{hostname}.local.",
            )
            
            print(f"[*] 自動発見サービスを開始: {local_ip}:{self.port}")
            self.zeroconf.register_service(self.service_info)
        except Exception as e:
            print(f"[!] 自動発見サービスの開始に失敗しました: {e}")

    def stop(self):
        try:
            if self.service_info:
                self.zeroconf.unregister_service(self.service_info)
            self.zeroconf.close()
        except:
            pass

# ── 起動処理 ──────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    # ポート5000で起動 (Androidアプリのデフォルト)
    port = 5000
    
    print("==================================================")
    print("CrossVision F 管理サーバー 起動中")
    print(f"管理画面: http://localhost:{port}/admin")
    print("APIキー認証と、自動発見サービスが有効です")
    print("==================================================")
    
    discovery = DiscoveryServer(port)
    discovery.start()
    
    try:
        uvicorn.run(app, host="0.0.0.0", port=port)
    finally:
        discovery.stop()
