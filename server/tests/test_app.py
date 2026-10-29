"""「吃了吗」服务端接口测试：server/.venv/bin/python -m pytest server/tests"""
import hashlib
import importlib
import os
import sqlite3
import sys

import pytest
from fastapi.testclient import TestClient

SERVER_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
P = "/api/te"


def load_app(data_dir):
    os.environ["TODAYEAT_DATA"] = str(data_dir)
    sys.path.insert(0, SERVER_DIR)
    try:
        sys.modules.pop("app", None)
        return importlib.import_module("app")
    finally:
        sys.path.remove(SERVER_DIR)


def make_auth(username: str, password: str) -> str:
    """与客户端一致：PBKDF2(password, "todayeat-v1:"+lower(trim(username)))，取后 32 字节 hex。测试用少轮数提速。"""
    salt = ("todayeat-v1:" + username.strip().lower()).encode()
    return hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 1000, 64)[32:].hex()


@pytest.fixture
def env(tmp_path):
    mod = load_app(tmp_path)
    client = TestClient(mod.app, raise_server_exceptions=False)
    return mod, client, tmp_path


def register(client, username, password="secret1", device="pixel"):
    return client.post(f"{P}/auth/register", json={"username": username, "auth": make_auth(username, password), "device": device})


def login(client, username, password="secret1", device="pixel"):
    return client.post(f"{P}/auth/login", json={"username": username, "auth": make_auth(username, password), "device": device})


def bearer(token):
    return {"Authorization": f"Bearer {token}"}


def assert_error(r, status):
    assert r.status_code == status, r.text
    body = r.json()
    assert body["status"] == "error"
    assert isinstance(body["detail"], str) and body["detail"]
    return body["detail"]


# ───────────────────────── 基础与错误格式 ─────────────────────────

def test_health_and_db_created(env):
    mod, client, data = env
    r = client.get(f"{P}/health")
    assert r.status_code == 200 and r.json() == {"status": "ok"}
    with sqlite3.connect(data / "todayeat.db") as conn:
        assert conn.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert {"users", "sessions", "sync_blobs"} <= tables
    assert (data / "photos").is_dir()


def test_error_format_is_unified(env):
    _, client, _ = env
    assert assert_error(client.get(f"{P}/nope"), 404) == "接口不存在"
    assert_error(client.delete(f"{P}/health"), 405)
    # 请求体不是 JSON / 缺字段
    assert_error(client.post(f"{P}/auth/login", content=b"not json", headers={"Content-Type": "application/json"}), 400)
    assert_error(client.post(f"{P}/auth/register", json={"username": "abc"}), 400)


def test_unauthenticated_returns_json_401(env):
    _, client, _ = env
    pid = "a" * 32
    for method, path in [
        ("get", "/auth/me"), ("post", "/auth/logout"), ("get", "/sync"), ("get", "/photos"),
        ("get", f"/photos/{pid}"), ("delete", f"/photos/{pid}"),
    ]:
        assert_error(getattr(client, method)(P + path), 401)
    assert_error(client.put(f"{P}/sync", json={"base_version": 0, "blob": "x"}), 401)
    assert_error(client.put(f"{P}/photos/{pid}", content=b"x"), 401)
    assert_error(client.get(f"{P}/sync", headers=bearer("deadbeef")), 401)
    assert_error(client.get(f"{P}/sync", headers={"Authorization": "Basic abc"}), 401)


# ───────────────────────── 账号 ─────────────────────────

def test_register_login_and_duplicate(env):
    mod, client, data = env
    r = register(client, "Alice")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok" and body["user"]["username"] == "Alice" and len(body["token"]) == 64

    # 大小写不敏感唯一
    assert_error(register(client, "alice"), 409)
    # 中文用户名、2 位
    assert register(client, "小明").status_code == 200

    # 登录：大小写不敏感（客户端派生时用小写用户名，auth 一致）
    r = login(client, "ALICE")
    assert r.status_code == 200 and r.json()["user"]["id"] == body["user"]["id"]

    # 服务器不存 auth 原文，也不存令牌原文
    with sqlite3.connect(data / "todayeat.db") as conn:
        auth_hash, salt = conn.execute("SELECT auth_hash, auth_salt FROM users WHERE id = ?", (body["user"]["id"],)).fetchone()
        tokens = [r[0] for r in conn.execute("SELECT token_hash FROM sessions")]
    raw = make_auth("Alice", "secret1")
    assert auth_hash != raw and len(salt) == 32
    assert auth_hash == hashlib.pbkdf2_hmac("sha256", raw.encode(), bytes.fromhex(salt), 100_000).hex()
    assert body["token"] not in tokens and hashlib.sha256(body["token"].encode()).hexdigest() in tokens


def test_login_failures(env):
    _, client, _ = env
    register(client, "bob")
    assert assert_error(login(client, "bob", "wrong!!"), 401) == "用户名或密码不对"
    assert assert_error(login(client, "nobody"), 401) == "用户名或密码不对"


def test_input_validation(env):
    _, client, _ = env
    good = make_auth("x", "y")
    cases = [
        {"username": "a", "auth": good},                 # 太短
        {"username": "a" * 33, "auth": good},            # 太长
        {"username": "bad name", "auth": good},          # 空格
        {"username": "bad-name", "auth": good},          # 连字符
        {"username": "okname", "auth": "z" * 64},        # 非 hex
        {"username": "okname", "auth": "a" * 63},        # 长度不对
        {"username": "okname", "auth": "pass1234"},      # 明文密码
    ]
    for c in cases:
        assert_error(client.post(f"{P}/auth/register", json=c), 400)
    assert_error(client.post(f"{P}/auth/login", json={"username": "okname", "auth": "z" * 64}), 400)


def test_multiple_sessions_logout_and_eviction(env):
    _, client, _ = env
    a = register(client, "carol", device="phone").json()["token"]
    b = login(client, "carol", device="tablet").json()["token"]
    for t in (a, b):
        r = client.get(f"{P}/auth/me", headers=bearer(t))
        assert r.status_code == 200 and r.json()["user"]["username"] == "carol"

    # 登出只删除当前会话
    assert client.post(f"{P}/auth/logout", headers=bearer(b)).json() == {"status": "ok"}
    assert_error(client.get(f"{P}/auth/me", headers=bearer(b)), 401)
    assert client.get(f"{P}/auth/me", headers=bearer(a)).status_code == 200

    # 再登录 10 次：最多保留 10 个会话，最久未用的被淘汰
    newer = [login(client, "carol").json()["token"] for _ in range(10)]
    assert_error(client.get(f"{P}/auth/me", headers=bearer(a)), 401)
    for t in newer:
        assert client.get(f"{P}/auth/me", headers=bearer(t)).status_code == 200


def test_fail_rate_limit(env):
    _, client, _ = env
    register(client, "dave")
    for _ in range(20):
        assert_error(login(client, "dave", "wrong!!"), 401)
    # 失败 20 次后连正确密码也被挡
    assert_error(login(client, "dave"), 429)
    assert_error(register(client, "newone"), 429)


def test_request_rate_limit(env):
    _, client, _ = env
    for _ in range(120):
        assert client.get(f"{P}/health").status_code == 200
    assert_error(client.get(f"{P}/health"), 429)
    # 不同 IP 不受影响
    assert client.get(f"{P}/health", headers={"CF-Connecting-IP": "1.2.3.4"}).status_code == 200


# ───────────────────────── 同步 ─────────────────────────

def test_sync_versions_and_conflict(env):
    _, client, _ = env
    h = bearer(register(client, "erin").json()["token"])
    assert client.get(f"{P}/sync", headers=h).json() == {"status": "ok", "version": 0, "blob": None, "updated_at": 0}

    r = client.put(f"{P}/sync", headers=h, json={"base_version": 0, "blob": "cipher-1"})
    assert r.status_code == 200 and r.json()["version"] == 1 and r.json()["updated_at"] > 0

    # 另一台设备还停在版本 0，提交会冲突
    assert_error(client.put(f"{P}/sync", headers=h, json={"base_version": 0, "blob": "stale"}), 409)
    assert_error(client.put(f"{P}/sync", headers=h, json={"base_version": 5, "blob": "future"}), 409)

    r = client.put(f"{P}/sync", headers=h, json={"base_version": 1, "blob": "cipher-2"})
    assert r.json()["version"] == 2
    body = client.get(f"{P}/sync", headers=h).json()
    assert body["version"] == 2 and body["blob"] == "cipher-2"

    # 数据按用户隔离
    other = bearer(register(client, "frank").json()["token"])
    assert client.get(f"{P}/sync", headers=other).json()["version"] == 0


def test_sync_validation(env):
    _, client, _ = env
    h = bearer(register(client, "gina").json()["token"])
    assert_error(client.put(f"{P}/sync", headers=h, json={"base_version": 0, "blob": ""}), 400)
    assert_error(client.put(f"{P}/sync", headers=h, json={"base_version": -1, "blob": "x"}), 400)
    assert_error(client.put(f"{P}/sync", headers=h, json={"base_version": 0, "blob": "x" * (2 * 1024 * 1024 + 1)}), 400)
    assert client.put(f"{P}/sync", headers=h, json={"base_version": 0, "blob": "x" * (2 * 1024 * 1024)}).status_code == 200


# ───────────────────────── 照片 ─────────────────────────

PID = "0123456789abcdef0123456789abcdef"
PID2 = "fedcba9876543210fedcba9876543210"
OCTET = {"Content-Type": "application/octet-stream"}


def test_photo_put_get_list_delete(env):
    mod, client, data = env
    reg = register(client, "henry").json()
    h = {**bearer(reg["token"]), **OCTET}
    blob = os.urandom(1000)

    assert client.get(f"{P}/photos", headers=h).json() == {"status": "ok", "ids": []}
    r = client.put(f"{P}/photos/{PID}", headers=h, content=blob)
    assert r.status_code == 200 and r.json() == {"status": "ok"}
    # 幂等：再传一次（覆盖）
    assert client.put(f"{P}/photos/{PID}", headers=h, content=blob).status_code == 200
    assert client.put(f"{P}/photos/{PID2}", headers=h, content=b"second").status_code == 200

    assert client.get(f"{P}/photos", headers=h).json()["ids"] == sorted([PID, PID2])
    r = client.get(f"{P}/photos/{PID}", headers=h)
    assert r.status_code == 200 and r.content == blob
    assert r.headers["content-type"] == "application/octet-stream"

    # 落盘位置与原子写入（不留临时文件）
    folder = data / "photos" / str(reg["user"]["id"])
    assert sorted(os.listdir(folder)) == sorted([f"{PID}.bin", f"{PID2}.bin"])

    assert client.delete(f"{P}/photos/{PID}", headers=h).json() == {"status": "ok"}
    assert client.delete(f"{P}/photos/{PID}", headers=h).status_code == 200   # 幂等
    assert assert_error(client.get(f"{P}/photos/{PID}", headers=h), 404) == "照片不存在"
    assert client.get(f"{P}/photos", headers=h).json()["ids"] == [PID2]


def test_photo_isolated_between_users(env):
    _, client, _ = env
    a = {**bearer(register(client, "ivan").json()["token"]), **OCTET}
    b = {**bearer(register(client, "judy").json()["token"]), **OCTET}
    client.put(f"{P}/photos/{PID}", headers=a, content=b"secret-of-ivan")

    assert client.get(f"{P}/photos", headers=b).json()["ids"] == []
    assert_error(client.get(f"{P}/photos/{PID}", headers=b), 404)
    # 另一用户删同 id 不影响原主人
    client.delete(f"{P}/photos/{PID}", headers=b)
    assert client.get(f"{P}/photos/{PID}", headers=a).content == b"secret-of-ivan"
    # 另一用户上传同 id 只写进自己的目录
    client.put(f"{P}/photos/{PID}", headers=b, content=b"judy")
    assert client.get(f"{P}/photos/{PID}", headers=a).content == b"secret-of-ivan"
    assert client.get(f"{P}/photos/{PID}", headers=b).content == b"judy"


def test_photo_invalid_id(env):
    _, client, _ = env
    h = {**bearer(register(client, "kate").json()["token"]), **OCTET}
    for bad in ["ABCDEF0123456789ABCDEF0123456789", "abc", "g" * 32, "a" * 33, "..%2F..%2Fx"]:
        r = client.put(f"{P}/photos/{bad}", headers=h, content=b"x")
        assert r.status_code in (400, 404), (bad, r.status_code)
        assert r.json()["status"] == "error"
        assert client.get(f"{P}/photos/{bad}", headers=h).json()["status"] == "error"
        assert client.delete(f"{P}/photos/{bad}", headers=h).json()["status"] == "error"
    assert_error(client.put(f"{P}/photos/{'g' * 32}", headers=h, content=b"x"), 400)
    assert_error(client.put(f"{P}/photos/{PID}", headers=h, content=b""), 400)


def test_photo_size_limits(env):
    _, client, _ = env
    h = {**bearer(register(client, "leo").json()["token"]), **OCTET}
    four_mb = 4 * 1024 * 1024
    assert client.put(f"{P}/photos/{PID}", headers=h, content=b"\0" * four_mb).status_code == 200
    assert_error(client.put(f"{P}/photos/{PID2}", headers=h, content=b"\0" * (four_mb + 1)), 413)
    # 超过全局上限：中间件按 Content-Length 直接拒绝
    assert_error(client.put(f"{P}/photos/{PID2}", headers=h, content=b"\0" * (6 * 1024 * 1024)), 413)

    # 分块传输（无 Content-Length）同样被拦下
    def chunks():
        for _ in range(12):
            yield b"\0" * (512 * 1024)

    assert_error(client.put(f"{P}/photos/{PID2}", headers=h, content=chunks()), 413)
    assert PID2 not in client.get(f"{P}/photos", headers=h).json()["ids"]


def test_photo_count_limit(env, monkeypatch):
    mod, client, _ = env
    monkeypatch.setattr(mod, "MAX_PHOTOS_PER_USER", 2)
    h = {**bearer(register(client, "mia").json()["token"]), **OCTET}
    ids = ["%032x" % i for i in range(3)]
    assert client.put(f"{P}/photos/{ids[0]}", headers=h, content=b"1").status_code == 200
    assert client.put(f"{P}/photos/{ids[1]}", headers=h, content=b"2").status_code == 200
    assert_error(client.put(f"{P}/photos/{ids[2]}", headers=h, content=b"3"), 409)
    # 覆盖已有的照片不受上限影响
    assert client.put(f"{P}/photos/{ids[1]}", headers=h, content=b"2b").status_code == 200
    # 删掉一张后可以再传
    client.delete(f"{P}/photos/{ids[0]}", headers=h)
    assert client.put(f"{P}/photos/{ids[2]}", headers=h, content=b"3").status_code == 200
