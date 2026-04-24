"""
鉄骨文字認識アプリ - 開発用ローカルサーバー
起動方法: uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""

from fastapi import FastAPI, Response, Security, HTTPException, Depends
from fastapi.security.api_key import APIKeyHeader
from fastapi.responses import HTMLResponse
from pydantic import BaseModel
from typing import List
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

class RegistrationResponse(BaseModel):
    success: bool
    message: str | None = None

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
        "received_at": datetime.now().isoformat(),
    }
    data.append(entry)
    save_data(data)
    print(f"[受信 {entry['received_at']}] 区分={entry['division']} 製品番号={entry['product_numbers']}")
    return RegistrationResponse(success=True, message=f"{len(req.product_numbers)}件を保存しました")

@app.get("/api/registrations")
def get_registrations():
    return load_data()

@app.get("/health")
def health():
    return {"status": "ok"}

@app.get("/api/export/csv")
def export_csv():
    data = load_data()
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["ID", "区分", "製品番号", "受信日時", "デバイスID"])
    for entry in data:
        div = "入庫" if entry.get("division") == "start" else "出庫"
        numbers = ", ".join(entry.get("product_numbers", []))
        received = entry.get("received_at", "")[:19].replace("T", " ")
        writer.writerow([entry.get("id"), div, numbers, received, entry.get("device_id", "")])
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
