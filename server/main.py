"""
鉄骨文字認識アプリ - 開発用ローカルサーバー
起動方法: uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""

from fastapi import FastAPI, Response, Security, HTTPException, Depends, UploadFile, File
from fastapi.security.api_key import APIKeyHeader
from fastapi.responses import HTMLResponse, FileResponse
from pydantic import BaseModel
from typing import List, Optional
from datetime import datetime
import json, os, csv, io, socket, starlette.status, unicodedata
from zeroconf import IPVersion, ServiceInfo, Zeroconf

app = FastAPI(title="鉄骨認識サーバー（開発用）")

BASE_DIR = os.path.dirname(__file__)

# ── モデル ──────────────────────────────────────────────

class ProductLabelRequest(BaseModel):
    code: str

class ConstructionRequest(BaseModel):
    name: str
    code: str
    isActive: bool = True

class ProcessRequest(BaseModel):
    constructionId: int
    name: str
    code: str
    isActive: bool = True

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
PRODUCT_LABELS_FILE = os.path.join(BASE_DIR, "product_labels.json")
CONSTRUCTIONS_FILE = os.path.join(BASE_DIR, "constructions.json")
PROCESSES_FILE = os.path.join(BASE_DIR, "processes.json")

def load_data() -> list:
    if not os.path.exists(DATA_FILE):
        return []
    with open(DATA_FILE, "r", encoding="utf-8") as f:
        return json.load(f)

def save_data(data: list):
    with open(DATA_FILE, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

def load_json_list(file_path: str, default_val=None) -> list:
    if default_val is None:
        default_val = []
    if not os.path.exists(file_path):
        return default_val
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            return json.load(f)
    except:
        return default_val

def save_json_list(file_path: str, data: list):
    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

def normalize_product_code(code: str) -> str:
    """製品コードを半角大文字に正規化し、許可されたASCII文字以外を除外する"""
    # NFKC正規化で全角英数字を半角に
    normalized = unicodedata.normalize('NFKC', code).upper().strip()
    # 許可されたASCII文字(0x20〜0x7E)のみを残す
    filtered = "".join(c for c in normalized if 0x20 <= ord(c) <= 0x7E)
    return filtered

# ── API ─────────────────────────────────────────────────

@app.post("/api/registrations", response_model=RegistrationResponse)
def post_registration(req: RegistrationRequest, api_key: str = Depends(get_api_key)):
    data = load_data()
    # 送信された製品番号ごとに別々のレコードとして個別に保存
    for product_number in req.product_numbers:
        entry = {
            "id": len(data) + 1,
            "process_id": req.process_id,
            "division": req.division,
            "worker_id": req.worker_id,
            "device_id": req.device_id,
            "registered_at": req.registered_at,
            "product_numbers": [product_number],
            "construction_name": req.construction_name,
            "process_name": req.process_name,
            "received_at": datetime.now().isoformat(),
        }
        data.append(entry)
        print(f"[受信 {entry['received_at']}] 区分={entry['division']} 製品番号={entry['product_numbers']}")
    
    save_data(data)
    return RegistrationResponse(success=True, message=f"{len(req.product_numbers)}件を個別のレコードとして保存しました")

@app.get("/api/registrations")
def get_registrations():
    return load_data()

@app.get("/api/product-labels")
def get_product_labels():
    # 万が一ファイルがない場合の初期ダミーデータ
    default_labels = ["H150X150X7", "B1Sb30N-7A", "C200X200X8"]
    return load_json_list(PRODUCT_LABELS_FILE, default_labels)

@app.post("/api/product-labels")
def add_product_label(req: ProductLabelRequest, api_key: str = Depends(get_api_key)):
    labels = load_json_list(PRODUCT_LABELS_FILE, [])
    code = normalize_product_code(req.code)
    if not code:
        raise HTTPException(status_code=400, detail="有効な文字が含まれていません")
    if code not in labels:
        labels.append(code)
        save_json_list(PRODUCT_LABELS_FILE, labels)
    return {"success": True, "code": code}

@app.delete("/api/product-labels/{code}")
def delete_product_label(code: str, api_key: str = Depends(get_api_key)):
    labels = load_json_list(PRODUCT_LABELS_FILE, [])
    labels = [l for l in labels if l != code]
    save_json_list(PRODUCT_LABELS_FILE, labels)
    return {"success": True}

@app.post("/api/product-labels/import")
def import_product_labels(file: UploadFile = File(...), api_key: str = Depends(get_api_key)):
    content = file.file.read().decode("utf-8-sig") # BOM付きにも対応
    labels = []
    reader = csv.reader(io.StringIO(content))
    # ヘッダーがあるかもしれないが、1列目だけ取れればOKとする
    for row in reader:
        if row and row[0].strip():
            raw_code = row[0].strip()
            # ヘッダー文字列っぽければスキップ
            if raw_code == "製品番号" or raw_code.lower() == "code":
                continue
            cleaned = normalize_product_code(raw_code)
            if cleaned:
                labels.append(cleaned)
    # 洗い替え
    unique_labels = list(set(labels))
    save_json_list(PRODUCT_LABELS_FILE, unique_labels)
    return {"success": True, "count": len(unique_labels)}

@app.get("/api/export/product-labels/csv")
def export_product_labels_csv():
    labels = load_json_list(PRODUCT_LABELS_FILE, [])
    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["製品番号"])
    for label in labels:
        writer.writerow([label])
    csv_bytes = ("\ufeff" + output.getvalue()).encode("utf-8")
    return Response(
        content=csv_bytes,
        media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=product_labels.csv"}
    )

@app.get("/api/constructions")
def get_constructions():
    default_constructions = [
        {"id": 1, "name": "F-M_FQ", "code": "20251111202F", "isActive": True},
        {"id": 2, "name": "新社屋建設工事", "code": "20250001", "isActive": True}
    ]
    return load_json_list(CONSTRUCTIONS_FILE, default_constructions)

@app.post("/api/constructions")
def add_construction(req: ConstructionRequest, api_key: str = Depends(get_api_key)):
    data = load_json_list(CONSTRUCTIONS_FILE, [])
    new_id = max([d.get("id", 0) for d in data], default=0) + 1
    data.append({"id": new_id, "name": req.name, "code": req.code, "isActive": req.isActive})
    save_json_list(CONSTRUCTIONS_FILE, data)
    return {"success": True}

@app.delete("/api/constructions/{cid}")
def delete_construction(cid: int, api_key: str = Depends(get_api_key)):
    data = load_json_list(CONSTRUCTIONS_FILE, [])
    data = [d for d in data if d.get("id") != cid]
    save_json_list(CONSTRUCTIONS_FILE, data)
    return {"success": True}

@app.post("/api/constructions/import")
def import_constructions(file: UploadFile = File(...), api_key: str = Depends(get_api_key)):
    content = file.file.read().decode("utf-8-sig")
    data = []
    reader = csv.reader(io.StringIO(content))
    header = next(reader, None)
    for i, row in enumerate(reader, start=1):
        if len(row) >= 2:
            data.append({"id": i, "name": row[0].strip(), "code": row[1].strip(), "isActive": True})
    save_json_list(CONSTRUCTIONS_FILE, data)
    return {"success": True, "count": len(data)}

@app.get("/api/processes")
def get_processes():
    default_processes = [
        {"id": 1, "constructionId": 1, "name": "FINS", "code": "FINS", "isActive": True},
        {"id": 2, "constructionId": 1, "name": "FAS検査", "code": "FAS", "isActive": True},
        {"id": 3, "constructionId": 1, "name": "FAILA0", "code": "FAIL", "isActive": True},
        {"id": 4, "constructionId": 1, "name": "FAW検査", "code": "FAW", "isActive": True},
        {"id": 5, "constructionId": 1, "name": "CINJA0", "code": "CINJ", "isActive": True},
        {"id": 6, "constructionId": 1, "name": "建て方管理", "code": "BLD", "isActive": True},
        {"id": 7, "constructionId": 2, "name": "一次加工", "code": "P01", "isActive": True},
        {"id": 8, "constructionId": 2, "name": "組立", "code": "P02", "isActive": True},
        {"id": 9, "constructionId": 2, "name": "溶接", "code": "P03", "isActive": True},
        {"id": 10, "constructionId": 2, "name": "塗装", "code": "P04", "isActive": True},
        {"id": 11, "constructionId": 2, "name": "検査", "code": "P05", "isActive": True},
        {"id": 12, "constructionId": 3, "name": "FINS", "code": "FINS", "isActive": True},
        {"id": 13, "constructionId": 3, "name": "FAS検査", "code": "FAS", "isActive": True},
        {"id": 14, "constructionId": 3, "name": "FAILA0", "code": "FAIL", "isActive": True},
        {"id": 15, "constructionId": 3, "name": "FAW検査", "code": "FAW", "isActive": True},
        {"id": 16, "constructionId": 3, "name": "CINJA0", "code": "CINJ", "isActive": True},
        {"id": 17, "constructionId": 3, "name": "建て方管理", "code": "BLD", "isActive": True}
    ]
    return load_json_list(PROCESSES_FILE, default_processes)

@app.post("/api/processes")
def add_process(req: ProcessRequest, api_key: str = Depends(get_api_key)):
    data = load_json_list(PROCESSES_FILE, [])
    new_id = max([d.get("id", 0) for d in data], default=0) + 1
    data.append({"id": new_id, "constructionId": req.constructionId, "name": req.name, "code": req.code, "isActive": req.isActive})
    save_json_list(PROCESSES_FILE, data)
    return {"success": True}

@app.delete("/api/processes/{pid}")
def delete_process(pid: int, api_key: str = Depends(get_api_key)):
    data = load_json_list(PROCESSES_FILE, [])
    data = [d for d in data if d.get("id") != pid]
    save_json_list(PROCESSES_FILE, data)
    return {"success": True}

@app.post("/api/processes/import")
def import_processes(file: UploadFile = File(...), api_key: str = Depends(get_api_key)):
    content = file.file.read().decode("utf-8-sig")
    data = []
    reader = csv.reader(io.StringIO(content))
    header = next(reader, None)
    for i, row in enumerate(reader, start=1):
        if len(row) >= 3:
            try:
                cid = int(row[0].strip())
            except:
                cid = 1
            data.append({"id": i, "constructionId": cid, "name": row[1].strip(), "code": row[2].strip(), "isActive": True})
    save_json_list(PROCESSES_FILE, data)
    return {"success": True, "count": len(data)}

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

@app.get("/download/app.apk")
def download_apk():
    """APKファイルをダウンロードするエンドポイント"""
    apk_path = os.path.join(BASE_DIR, "app.apk")
    if not os.path.exists(apk_path):
        raise HTTPException(status_code=404, detail="APKファイルがサーバー上に見つかりません。")
    return FileResponse(apk_path, media_type="application/vnd.android.package-archive", filename="app.apk")

@app.get("/api/server-info")
def get_server_info():
    """サーバーのローカルIPとポートを返すAPI"""
    hostname = socket.gethostname()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        local_ip = socket.gethostbyname(hostname)
    return {"local_ip": local_ip, "port": 5000}

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

@app.get("/download", response_class=HTMLResponse)
def download_page():
    """アプリダウンロード専用ページのHTMLをファイルから読み込んで返す"""
    file_path = os.path.join(BASE_DIR, "download.html")
    if os.path.exists(file_path):
        with open(file_path, "r", encoding="utf-8") as f:
            return f.read()
    return "download.html not found"
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
    
    # 起動時のIP取得処理
    hostname = socket.gethostname()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
    except Exception:
        local_ip = socket.gethostbyname(hostname)

    print("==================================================")
    print("CrossVision F 管理サーバー 起動中")
    print(f"管理画面: http://localhost:{port}/admin")
    print(f"アプリダウンロード用URL: http://{local_ip}:{port}/download")
    print("APIキー認証と、自動発見サービスが有効です")
    print("==================================================")
    
    discovery = DiscoveryServer(port)
    discovery.start()
    
    try:
        uvicorn.run(app, host="0.0.0.0", port=port)
    finally:
        discovery.stop()
