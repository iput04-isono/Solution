"""
CrossVision F - Flask 管理サーバー
=====================================
起動方法:
    pip install -r requirements.txt
    python app.py

アクセス:
    管理画面: http://localhost:5000/admin
    API エンドポイント: POST http://[このPCのIP]:5000/api/registrations

Android アプリから呼ばれる API:
    POST /api/registrations
    Body (JSON):
    {
        "product_code": "BISb30N-7A",
        "construction_name": "〇〇橋工事",
        "process_name": "塗装",
        "user_id": "user001",
        "registered_at": 1713311400000  # Unix タイムスタンプ(ms)
    }
"""

import csv
import io
from datetime import datetime, timezone, timedelta
from flask import Flask, request, jsonify, render_template, Response
from flask_sqlalchemy import SQLAlchemy

app = Flask(__name__)
app.config["SQLALCHEMY_DATABASE_URI"] = "sqlite:///crossvision.db"
app.config["SQLALCHEMY_TRACK_MODIFICATIONS"] = False

db = SQLAlchemy(app)

JST = timezone(timedelta(hours=9))


# ──────────────────────────────────────────────────
# Jinja2 カスタムフィルター
# ──────────────────────────────────────────────────

@app.template_filter("ms_to_jst")
def ms_to_jst(ms):
    """Unix タイムスタンプ(ms) を JST の日時文字列に変換する Jinja2 フィルター"""
    if not ms:
        return "-"
    try:
        return datetime.fromtimestamp(int(ms) / 1000, tz=JST).strftime("%Y-%m-%d %H:%M")
    except Exception:
        return "-"

# ──────────────────────────────────────────────────
# モデル定義
# ──────────────────────────────────────────────────

class Registration(db.Model):
    """OCR 認識データの登録レコード"""
    __tablename__ = "registrations"

    id               = db.Column(db.Integer,  primary_key=True, autoincrement=True)
    product_code     = db.Column(db.String(100), nullable=False)
    construction_name= db.Column(db.String(200), nullable=False)
    process_name     = db.Column(db.String(200), nullable=False)
    user_id          = db.Column(db.String(100), nullable=False)
    registered_at    = db.Column(db.BigInteger, nullable=False)   # Unixタイムスタンプ(ms)
    received_at      = db.Column(db.DateTime, nullable=False, default=lambda: datetime.now(JST))
    device_ip        = db.Column(db.String(50), nullable=True)

    def to_dict(self):
        reg_dt = datetime.fromtimestamp(self.registered_at / 1000, tz=JST)
        return {
            "id":                self.id,
            "product_code":      self.product_code,
            "construction_name": self.construction_name,
            "process_name":      self.process_name,
            "user_id":           self.user_id,
            "registered_at":     reg_dt.strftime("%Y-%m-%d %H:%M:%S"),
            "received_at":       self.received_at.strftime("%Y-%m-%d %H:%M:%S"),
            "device_ip":         self.device_ip or "",
        }


# ──────────────────────────────────────────────────
# API エンドポイント（Android アプリ用）
# ──────────────────────────────────────────────────

@app.route("/api/registrations", methods=["POST"])
def post_registration():
    """Android アプリから OCR 認識データを受信して DB に保存する"""
    data = request.get_json(silent=True)
    if not data:
        return jsonify({"success": False, "message": "Invalid JSON"}), 400

    required_fields = ["product_code", "construction_name", "process_name", "user_id", "registered_at"]
    for field in required_fields:
        if field not in data:
            return jsonify({"success": False, "message": f"Missing field: {field}"}), 400

    try:
        reg = Registration(
            product_code      = str(data["product_code"]),
            construction_name = str(data["construction_name"]),
            process_name      = str(data["process_name"]),
            user_id           = str(data["user_id"]),
            registered_at     = int(data["registered_at"]),
            device_ip         = request.remote_addr,
        )
        db.session.add(reg)
        db.session.commit()
        return jsonify({"success": True, "id": reg.id}), 201

    except Exception as e:
        db.session.rollback()
        return jsonify({"success": False, "message": str(e)}), 500


@app.route("/api/registrations", methods=["GET"])
def get_registrations():
    """登録一覧を JSON で返す（REST API 用）"""
    regs = Registration.query.order_by(Registration.received_at.desc()).all()
    return jsonify([r.to_dict() for r in regs])


# ──────────────────────────────────────────────────
# 管理画面
# ──────────────────────────────────────────────────

@app.route("/admin")
def admin():
    """管理者向けデータ管理画面"""
    construction = request.args.get("construction", "").strip()
    process      = request.args.get("process", "").strip()
    user         = request.args.get("user", "").strip()

    query = Registration.query
    if construction:
        query = query.filter(Registration.construction_name.like(f"%{construction}%"))
    if process:
        query = query.filter(Registration.process_name.like(f"%{process}%"))
    if user:
        query = query.filter(Registration.user_id.like(f"%{user}%"))

    regs = query.order_by(Registration.received_at.desc()).all()
    total = Registration.query.count()

    return render_template(
        "admin.html",
        registrations=regs,
        total=total,
        filter_construction=construction,
        filter_process=process,
        filter_user=user,
    )


@app.route("/admin/export/csv")
def export_csv():
    """現在のフィルター条件で CSV ダウンロード"""
    construction = request.args.get("construction", "").strip()
    process      = request.args.get("process", "").strip()
    user         = request.args.get("user", "").strip()

    query = Registration.query
    if construction:
        query = query.filter(Registration.construction_name.like(f"%{construction}%"))
    if process:
        query = query.filter(Registration.process_name.like(f"%{process}%"))
    if user:
        query = query.filter(Registration.user_id.like(f"%{user}%"))

    regs = query.order_by(Registration.received_at.desc()).all()

    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["ID", "製品コード", "工事名", "工程名", "担当者ID", "登録日時", "受信日時", "デバイスIP"])
    for r in regs:
        d = r.to_dict()
        writer.writerow([
            d["id"], d["product_code"], d["construction_name"],
            d["process_name"], d["user_id"],
            d["registered_at"], d["received_at"], d["device_ip"],
        ])

    filename = f"crossvision_{datetime.now(JST).strftime('%Y%m%d_%H%M%S')}.csv"
    return Response(
        "\ufeff" + output.getvalue(),  # BOM付きUTF-8（Excel対応）
        mimetype="text/csv",
        headers={"Content-Disposition": f"attachment; filename={filename}"}
    )


@app.route("/admin/delete/<int:reg_id>", methods=["POST"])
def delete_registration(reg_id):
    """レコードを削除する"""
    reg = Registration.query.get_or_404(reg_id)
    db.session.delete(reg)
    db.session.commit()
    return jsonify({"success": True})


# ──────────────────────────────────────────────────
# 起動
# ──────────────────────────────────────────────────

if __name__ == "__main__":
    with app.app_context():
        db.create_all()
        print("=" * 50)
        print("CrossVision F 管理サーバー 起動中")
        print("管理画面: http://localhost:5000/admin")
        print("Android から接続する場合は PC の IP アドレスを使用してください")
        print("  例: http://192.168.x.x:5000/api/registrations")
        print("  PCのIPは ipconfig コマンドで確認できます")
        print("=" * 50)
    app.run(host="0.0.0.0", port=5000, debug=True)
