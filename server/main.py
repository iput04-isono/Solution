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
    return HTML_PAGE

# ── HTML（CSS・JS込み一体型） ────────────────────────────

HTML_PAGE = """<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1.0"/>
<title>鉄骨認識 ダッシュボード</title>
<style>
*,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
:root{
  --sidebar-w:220px;--bg:#f4f6fb;--surface:#fff;--border:#e8ecf3;
  --text:#1a1f36;--muted:#6b7280;--blue:#3b82f6;--blue-d:#2563eb;
  --green:#10b981;--red:#ef4444;--yellow:#f59e0b;
  --radius:12px;--shadow:0 2px 12px rgba(0,0,0,.07);
}
body{font-family:'Segoe UI','Helvetica Neue',sans-serif;background:var(--bg);color:var(--text);display:flex;min-height:100vh}

/* サイドバー */
.sidebar{width:var(--sidebar-w);background:#1e2340;color:#c8cde8;display:flex;flex-direction:column;position:fixed;top:0;left:0;bottom:0;z-index:100}
.sidebar-logo{display:flex;align-items:center;gap:10px;padding:22px 20px;border-bottom:1px solid #2d3355}
.logo-icon{font-size:24px}.logo-text{font-size:16px;font-weight:700;color:#fff;letter-spacing:.5px}
.sidebar-nav{flex:1;padding:16px 12px}
.nav-item{display:flex;align-items:center;gap:10px;padding:10px 14px;border-radius:8px;color:#8b92b8;text-decoration:none;font-size:14px;transition:background .15s,color .15s}
.nav-item:hover,.nav-item.active{background:#2d3355;color:#fff}
.sidebar-footer{padding:16px 20px;border-top:1px solid #2d3355}
.update-time{font-size:11px;color:#5a6080}

/* メイン */
.main-wrap{margin-left:var(--sidebar-w);flex:1;display:flex;flex-direction:column}
.topbar{background:var(--surface);border-bottom:1px solid var(--border);padding:16px 28px;display:flex;align-items:center;justify-content:space-between;position:sticky;top:0;z-index:50}
.page-title{font-size:18px;font-weight:700}
.topbar-actions{display:flex;gap:10px}
.content{padding:24px 28px;display:flex;flex-direction:column;gap:20px}

/* ボタン */
.btn{padding:8px 18px;border-radius:8px;border:none;font-size:13px;font-weight:500;cursor:pointer;transition:opacity .15s,transform .1s}
.btn:hover{opacity:.88}.btn:active{transform:scale(.97)}
.btn-primary{background:var(--blue);color:#fff}
.btn-outline{background:transparent;border:1px solid var(--border);color:var(--text)}
.btn-ghost{background:transparent;color:var(--muted);font-size:13px;padding:8px 12px}
.btn-ghost:hover{color:var(--text)}

/* カード */
.cards{display:grid;grid-template-columns:1fr;gap:16px}
.card{background:var(--surface);border-radius:var(--radius);padding:20px;display:flex;align-items:center;gap:16px;box-shadow:var(--shadow);border-top:3px solid transparent;transition:transform .15s}
.card:hover{transform:translateY(-2px)}
.card-blue{border-color:var(--blue)}.card-green{border-color:var(--green)}.card-red{border-color:var(--red)}.card-yellow{border-color:var(--yellow)}
.card-icon{font-size:28px}
.card-label{font-size:12px;color:var(--muted);margin-bottom:4px}
.card-value{font-size:30px;font-weight:800;line-height:1}
.card-blue .card-value{color:var(--blue)}.card-green .card-value{color:var(--green)}.card-red .card-value{color:var(--red)}.card-yellow .card-value{color:var(--yellow)}


/* フィルター */
.filter-bar{background:var(--surface);border-radius:var(--radius);padding:16px 20px;box-shadow:var(--shadow);display:flex;align-items:flex-end;gap:16px;flex-wrap:wrap}
.filter-group{display:flex;flex-direction:column;gap:5px}
.filter-search{flex:1;min-width:200px}
.filter-label{font-size:11px;font-weight:600;color:var(--muted);text-transform:uppercase;letter-spacing:.5px}
.filter-input{padding:8px 12px;border:1px solid var(--border);border-radius:8px;font-size:13px;color:var(--text);background:var(--bg);outline:none;transition:border-color .15s}
.filter-input:focus{border-color:var(--blue);background:#fff}
.filter-count{margin-left:auto;font-size:12px;color:var(--muted);align-self:center}

/* テーブル */
.table-section{background:var(--surface);border-radius:var(--radius);box-shadow:var(--shadow);overflow:hidden}
table{width:100%;border-collapse:collapse}
thead th{background:#f8f9fc;padding:12px 16px;font-size:12px;font-weight:700;color:var(--muted);text-align:left;border-bottom:2px solid var(--border);white-space:nowrap;text-transform:uppercase;letter-spacing:.4px}
th.sortable{cursor:pointer;user-select:none;position:relative;padding-right:28px}
th.sortable::after{content:'↕';position:absolute;right:10px;opacity:.35;font-size:11px}
th.sortable.asc::after{content:'↑';opacity:1;color:var(--blue)}
th.sortable.desc::after{content:'↓';opacity:1;color:var(--blue)}
th.sortable:hover{background:#eef2ff;color:var(--blue)}
tbody td{padding:12px 16px;border-bottom:1px solid var(--border);font-size:13px;vertical-align:middle}
tbody tr:last-child td{border-bottom:none}
tbody tr{transition:background .1s}
tbody tr:hover td{background:#f5f8ff}
.badge{display:inline-block;background:#eff6ff;color:var(--blue);border:1px solid #bfdbfe;border-radius:6px;padding:2px 8px;margin:2px;font-size:12px;font-weight:500}
.div-in{display:inline-block;background:#d1fae5;color:#065f46;border-radius:6px;padding:3px 10px;font-size:12px;font-weight:700}
.div-out{display:inline-block;background:#fee2e2;color:#991b1b;border-radius:6px;padding:3px 10px;font-size:12px;font-weight:700}
.empty-row{text-align:center;color:var(--muted);padding:48px;font-size:14px}
</style>
</head>
<body>

<aside class="sidebar">
  <div class="sidebar-logo"><span class="logo-icon">🏗</span><span class="logo-text">鉄骨認識</span></div>
  <nav class="sidebar-nav"><a href="#" class="nav-item active">📊 ダッシュボード</a></nav>
  <div class="sidebar-footer"><span id="lastUpdated" class="update-time">更新中...</span></div>
</aside>

<div class="main-wrap">
  <header class="topbar">
    <h1 class="page-title">ダッシュボード</h1>
    <div class="topbar-actions">
      <button class="btn btn-outline" onclick="fetchData()">🔄 更新</button>
      <button class="btn btn-primary" onclick="exportCsv()">⬇ CSV出力</button>
    </div>
  </header>

  <main class="content">
    <section class="cards">
      <div class="card card-blue">  <div class="card-icon">📦</div><div class="card-body"><div class="card-label">総登録件数</div><div class="card-value" id="cTotal">—</div></div></div>
    </section>


    <section class="filter-bar">
      <div class="filter-group"><label class="filter-label">日付</label><input type="date" id="filterDate" class="filter-input"/></div>
      <div class="filter-group"><label class="filter-label">区分</label>
        <select id="filterDiv" class="filter-input"><option value="">すべて</option><option value="start">入庫</option><option value="end">出庫</option></select>
      </div>
      <div class="filter-group filter-search"><label class="filter-label">製品番号</label><input type="text" id="filterSearch" class="filter-input" placeholder="キーワード検索..."/></div>
      <button class="btn btn-ghost" onclick="clearFilters()">✕ クリア</button>
      <div class="filter-count" id="countInfo"></div>
    </section>

    <section class="table-section">
      <table>
        <thead><tr>
          <th class="sortable" data-key="id">ID</th>
          <th class="sortable" data-key="division">区分</th>
          <th>製品番号</th>
          <th class="sortable" data-key="received_at">受信日時</th>
          <th class="sortable" data-key="device_id">デバイスID</th>
        </tr></thead>
        <tbody id="tableBody"><tr><td colspan="5" class="empty-row">読み込み中...</td></tr></tbody>
      </table>
    </section>
  </main>
</div>

<script>
let allData='',sortKey='id',sortDir='desc',barChart,pieChart;

async function fetchData(){
  const res=await fetch('/api/registrations');
  allData=await res.json();
  updateCards();updateCharts();updateTable();
  document.getElementById('lastUpdated').textContent='最終更新 '+new Date().toLocaleTimeString('ja-JP');
}

function updateCards(){
  document.getElementById('cTotal').textContent=allData.length;
}

function updateCharts(){}

function getFiltered(){
  const date=document.getElementById('filterDate').value;
  const div=document.getElementById('filterDiv').value;
  const search=document.getElementById('filterSearch').value.toLowerCase();
  return allData.filter(d=>{
    if(date&&!(d.received_at||'').startsWith(date))return false;
    if(div&&d.division!==div)return false;
    if(search&&!d.product_numbers.some(n=>n.toLowerCase().includes(search)))return false;
    return true;
  });
}

function clearFilters(){
  document.getElementById('filterDate').value='';
  document.getElementById('filterDiv').value='';
  document.getElementById('filterSearch').value='';
  updateTable();
}

function handleSort(key){
  sortDir=sortKey===key?(sortDir==='asc'?'desc':'asc'):'asc';
  sortKey=key;
  document.querySelectorAll('th.sortable').forEach(th=>{
    th.classList.remove('asc','desc');
    if(th.dataset.key===key)th.classList.add(sortDir);
  });
  updateTable();
}

function updateTable(){
  const filtered=getFiltered();
  const sorted=[...filtered].sort((a,b)=>{
    let va=a[sortKey]??'',vb=b[sortKey]??'';
    if(typeof va==='number'&&typeof vb==='number')return sortDir==='asc'?va-vb:vb-va;
    va=String(va).toLowerCase();vb=String(vb).toLowerCase();
    const c=va<vb?-1:va>vb?1:0;return sortDir==='asc'?c:-c;
  });
  const tbody=document.getElementById('tableBody');
  tbody.innerHTML=sorted.length===0
    ?'<tr><td colspan="5" class="empty-row">データがありません</td></tr>'
    :sorted.map(d=>`<tr>
      <td>${d.id}</td>
      <td>${d.division==='start'?'<span class="div-in">入庫</span>':'<span class="div-out">出庫</span>'}</td>
      <td>${(d.product_numbers||[]).map(n=>`<span class="badge">${n}</span>`).join('')}</td>
      <td>${(d.received_at||'').slice(0,19).replace('T',' ')}</td>
      <td>${d.device_id||''}</td>
    </tr>`).join('');
  document.getElementById('countInfo').textContent=`${filtered.length}件表示 / 全${allData.length}件`;
}

function exportCsv(){window.location.href='/api/export/csv'}

document.querySelectorAll('th.sortable').forEach(th=>th.addEventListener('click',()=>handleSort(th.dataset.key)));
['filterDate','filterDiv','filterSearch'].forEach(id=>document.getElementById(id).addEventListener('input',updateTable));

fetchData();
setInterval(fetchData,10000);
</script>
</body>
</html>"""
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
