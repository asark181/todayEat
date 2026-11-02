"""「吃了吗」服药打卡 · 账号与端到端加密同步服务（接口前缀 /api/te）。

- 账号：客户端只发来由密码派生的 auth（64 位 hex），服务器再用随机盐 PBKDF2-SHA256 存储，拿不到数据密钥。
- 会话：令牌 32 字节随机，库里只存 sha256；每账号最多保留 10 个会话（多设备）。
- 同步：只保存不透明密文 blob 与版本号；base_version 与当前版本不一致返回 409。
- 照片：密文文件存 <DATA>/photos/<user_id>/<id>.bin，临时文件 + rename 原子写入。
所有错误统一返回 JSON：{"status": "error", "detail": "<中文提示>"}。
"""
import hashlib
import hmac
import logging
import os
import re
import secrets
import sqlite3
import tempfile
import threading
import time
from collections import defaultdict, deque
from contextlib import closing

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

DATA_DIR = os.path.abspath(os.environ.get("TODAYEAT_DATA", "./data"))
DB_PATH = os.path.join(DATA_DIR, "todayeat.db")
PHOTO_DIR = os.path.join(DATA_DIR, "photos")

PREFIX = "/api/te"
MAX_BLOB = 2 * 1024 * 1024            # 同步快照密文上限（base64 字符数）
MAX_PHOTO = 4 * 1024 * 1024           # 单张照片密文上限
MAX_REQUEST = 5 * 1024 * 1024         # 任何请求体的硬上限（兜底，防止超大/分块请求吃内存）
MAX_PHOTOS_PER_USER = 3000
MAX_SESSIONS = 10
PBKDF2_ROUNDS = 100_000

HEX64 = re.compile(r"^[0-9a-fA-F]{64}$")
PHOTO_ID_RE = re.compile(r"^[0-9a-f]{32}$")
PHOTO_FILE_RE = re.compile(r"^([0-9a-f]{32})\.bin$")
USERNAME_RE = re.compile(r"^[A-Za-z0-9_一-鿿]{2,32}$")

MSG_USERNAME = "用户名需为 2-32 位字母、数字、下划线或中文"
MSG_AUTH = "登录凭据格式不对，请更新 App 后重试"
MSG_LOGIN_FAIL = "用户名或密码不对"
MSG_NO_LOGIN = "未登录或登录已失效"
MSG_SESSION_GONE = "登录已失效，请重新登录"
MSG_TOO_LARGE = "上传内容太大"

log = logging.getLogger("todayeat")
_lock = threading.Lock()


# ───────────────────────── 数据库 ─────────────────────────

def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init_db() -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    os.makedirs(PHOTO_DIR, exist_ok=True)
    with closing(db()) as conn, conn:
        conn.execute(
            """CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                auth_hash TEXT NOT NULL,
                auth_salt TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                last_login_at INTEGER NOT NULL
            )"""
        )
        # 每台设备一条会话，登录新设备不会挤掉其他设备
        conn.execute(
            """CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                device TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL
            )"""
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id)")
        conn.execute(
            """CREATE TABLE IF NOT EXISTS sync_blobs (
                user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                version INTEGER NOT NULL,
                blob TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )


init_db()


# ───────────────────────── 应用与请求体大小兜底 ─────────────────────────

app = FastAPI(title="TodayEat", docs_url=None, redoc_url=None, openapi_url=None)


def _error(status: int, detail: str) -> JSONResponse:
    return JSONResponse({"status": "error", "detail": detail}, status_code=status)


class BodyLimitMiddleware:
    """
    纯 ASGI 中间件：Content-Length 超限直接 413；没有 Content-Length（分块传输）时边读边计数，
    超限在 receive 中抛 413，由 FastAPI 透传给统一错误处理。nginx 也有 client_max_body_size，这里是第二道防线。
    """

    def __init__(self, app, limit: int = MAX_REQUEST):
        self.app = app
        self.limit = limit

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        for name, value in scope.get("headers", []):
            if name == b"content-length":
                try:
                    too_big = int(value) > self.limit
                except ValueError:
                    too_big = True
                if too_big:
                    return await _error(413, MSG_TOO_LARGE)(scope, receive, send)
                break

        received = 0

        async def limited_receive():
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.limit:
                    raise HTTPException(status_code=413, detail=MSG_TOO_LARGE)
            return message

        return await self.app(scope, limited_receive, send)


app.add_middleware(BodyLimitMiddleware)


# ───────────────────────── 统一错误响应 ─────────────────────────

@app.exception_handler(StarletteHTTPException)
async def _http_error(_: Request, exc: StarletteHTTPException):
    if exc.status_code == 404 and exc.detail == "Not Found":
        detail = "接口不存在"
    elif exc.status_code == 405:
        detail = "请求方法不支持"
    elif exc.detail == "There was an error parsing the body":
        # FastAPI 解析请求体失败时的英文提示，统一换成中文
        detail = "请求参数不正确"
    elif isinstance(exc.detail, str):
        detail = exc.detail
    else:
        detail = "请求失败"
    return _error(exc.status_code, detail)


@app.exception_handler(RequestValidationError)
async def _validation_error(_: Request, exc: RequestValidationError):
    fields = {str(e.get("loc", [""])[-1]) for e in exc.errors()}
    if "username" in fields:
        msg = MSG_USERNAME
    elif "auth" in fields:
        msg = MSG_AUTH
    elif "blob" in fields:
        msg = "同步数据为空或超过 2MB"
    elif "base_version" in fields:
        msg = "同步版本号不正确"
    else:
        msg = "请求参数不正确"
    return _error(400, msg)


@app.exception_handler(Exception)
async def _unexpected_error(_: Request, exc: Exception):
    log.exception("unhandled error: %s", exc)
    return _error(500, "服务器开小差了，请稍后重试")


# ───────────────────────── 限流（与记账一致） ─────────────────────────

_hits: dict[str, deque] = defaultdict(deque)
_fails: dict[str, deque] = defaultdict(deque)
_last_sweep = 0.0


def _client_ip(request: Request) -> str:
    cf = request.headers.get("CF-Connecting-IP")
    if cf:
        return cf.strip()
    fwd = request.headers.get("X-Forwarded-For")
    if fwd:
        return fwd.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def _sweep(now: float) -> None:
    """定期清理过期 IP 记录，避免内存随访问 IP 数无限增长"""
    global _last_sweep
    if now - _last_sweep < 300:
        return
    _last_sweep = now
    for store, window in ((_hits, 60), (_fails, 600)):
        for ip in [k for k, dq in store.items() if not dq or dq[-1] < now - window]:
            store.pop(ip, None)


def _check_rate(ip: str, limit: int = 120, window: int = 60) -> None:
    now = time.time()
    with _lock:
        _sweep(now)
        dq = _hits[ip]
        while dq and dq[0] < now - window:
            dq.popleft()
        if len(dq) >= limit:
            raise HTTPException(status_code=429, detail="请求过于频繁，请稍后再试")
        dq.append(now)


def _check_fail_rate(ip: str, limit: int = 20, window: int = 600) -> None:
    now = time.time()
    with _lock:
        dq = _fails[ip]
        while dq and dq[0] < now - window:
            dq.popleft()
        if len(dq) >= limit:
            raise HTTPException(status_code=429, detail="失败次数过多，请 10 分钟后再试")


def _record_fail(ip: str) -> None:
    with _lock:
        _fails[ip].append(time.time())


# ───────────────────────── 账号与会话 ─────────────────────────

def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def hash_auth(auth: str, salt: bytes | None = None) -> tuple[str, str]:
    if salt is None:
        salt = os.urandom(16)
    hashed = hashlib.pbkdf2_hmac("sha256", auth.encode("ascii"), salt, PBKDF2_ROUNDS)
    return hashed.hex(), salt.hex()


def verify_auth(auth: str, hashed_hex: str, salt_hex: str) -> bool:
    try:
        computed, _ = hash_auth(auth, bytes.fromhex(salt_hex))
    except ValueError:
        return False
    return hmac.compare_digest(computed, hashed_hex)


# 用户不存在时也跑一次 PBKDF2，避免通过响应时间判断用户名是否存在
_DUMMY_SALT = os.urandom(16).hex()
_DUMMY_HASH = hash_auth("0" * 64, bytes.fromhex(_DUMMY_SALT))[0]


class CredentialPayload(BaseModel):
    username: str = Field(..., min_length=1, max_length=64)
    auth: str = Field(..., min_length=64, max_length=64)
    device: str | None = Field(default=None, max_length=200)


def _clean_username(raw: str) -> str:
    username = raw.strip()
    if not USERNAME_RE.match(username):
        raise HTTPException(status_code=400, detail=MSG_USERNAME)
    return username


def _clean_auth(raw: str) -> str:
    if not HEX64.match(raw):
        raise HTTPException(status_code=400, detail=MSG_AUTH)
    return raw.lower()


def _clean_device(raw: str | None) -> str:
    return (raw or "").strip()[:64]


def _issue_token(conn: sqlite3.Connection, user_id: int, device: str, now: int) -> str:
    """为本次登录的设备新建会话；每个账号最多保留最近使用的 MAX_SESSIONS 个"""
    token = secrets.token_hex(32)
    conn.execute(
        "INSERT INTO sessions (token_hash, user_id, device, created_at, last_used_at) VALUES (?, ?, ?, ?, ?)",
        (_sha256(token), user_id, device, now, now),
    )
    conn.execute(
        "DELETE FROM sessions WHERE user_id = ? AND token_hash NOT IN "
        "(SELECT token_hash FROM sessions WHERE user_id = ? ORDER BY last_used_at DESC, created_at DESC, rowid DESC LIMIT ?)",
        (user_id, user_id, MAX_SESSIONS),
    )
    conn.execute("UPDATE users SET last_login_at = ? WHERE id = ?", (now, user_id))
    return token


def _bearer(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail=MSG_NO_LOGIN)
    token = authorization[len("Bearer "):].strip()
    if not token:
        raise HTTPException(status_code=401, detail=MSG_NO_LOGIN)
    return token


def _current_user(conn: sqlite3.Connection, authorization: str | None) -> tuple[int, str, str]:
    """返回 (user_id, username, token_hash)；令牌无效抛 401"""
    token_hash = _sha256(_bearer(authorization))
    row = conn.execute(
        "SELECT u.id, u.username FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?",
        (token_hash,),
    ).fetchone()
    if not row:
        raise HTTPException(status_code=401, detail=MSG_SESSION_GONE)
    conn.execute("UPDATE sessions SET last_used_at = ? WHERE token_hash = ?", (int(time.time()), token_hash))
    return row[0], row[1], token_hash


def _auth_user(authorization: str | None) -> int:
    with closing(db()) as conn, conn:
        return _current_user(conn, authorization)[0]


def _auth_response(user_id: int, username: str, token: str) -> dict:
    return {"status": "ok", "token": token, "user": {"id": user_id, "username": username}}


@app.get(f"{PREFIX}/health")
def health(request: Request):
    _check_rate(_client_ip(request))
    return {"status": "ok"}


@app.post(f"{PREFIX}/auth/register")
def register(payload: CredentialPayload, request: Request):
    ip = _client_ip(request)
    _check_rate(ip)
    _check_fail_rate(ip)
    username = _clean_username(payload.username)
    auth = _clean_auth(payload.auth)
    device = _clean_device(payload.device)
    now = int(time.time())
    auth_hash, auth_salt = hash_auth(auth)

    with _lock, closing(db()) as conn, conn:
        if conn.execute("SELECT 1 FROM users WHERE username = ?", (username,)).fetchone():
            _fails[ip].append(time.time())   # 已持有 _lock，直接记一次失败
            raise HTTPException(status_code=409, detail="这个用户名已经有人用了，换一个或直接登录")
        cur = conn.execute(
            "INSERT INTO users (username, auth_hash, auth_salt, created_at, last_login_at) VALUES (?, ?, ?, ?, ?)",
            (username, auth_hash, auth_salt, now, now),
        )
        user_id = cur.lastrowid
        token = _issue_token(conn, user_id, device, now)

    return _auth_response(user_id, username, token)


@app.post(f"{PREFIX}/auth/login")
def login(payload: CredentialPayload, request: Request):
    ip = _client_ip(request)
    _check_rate(ip)
    _check_fail_rate(ip)
    username = payload.username.strip()
    if not HEX64.match(payload.auth):
        raise HTTPException(status_code=400, detail=MSG_AUTH)
    auth = payload.auth.lower()
    device = _clean_device(payload.device)
    now = int(time.time())

    with closing(db()) as conn:
        row = conn.execute(
            "SELECT id, username, auth_hash, auth_salt FROM users WHERE username = ?", (username,)
        ).fetchone()
    # PBKDF2 较慢，放在锁外计算
    if row:
        ok = verify_auth(auth, row[2], row[3])
    else:
        verify_auth(auth, _DUMMY_HASH, _DUMMY_SALT)
        ok = False
    if not ok:
        _record_fail(ip)
        raise HTTPException(status_code=401, detail=MSG_LOGIN_FAIL)

    with _lock, closing(db()) as conn, conn:
        token = _issue_token(conn, row[0], device, now)
    return _auth_response(row[0], row[1], token)


@app.post(f"{PREFIX}/auth/logout")
def logout(request: Request, authorization: str = Header(None)):
    _check_rate(_client_ip(request))
    with _lock, closing(db()) as conn, conn:
        _, _, token_hash = _current_user(conn, authorization)
        conn.execute("DELETE FROM sessions WHERE token_hash = ?", (token_hash,))
    return {"status": "ok"}


@app.get(f"{PREFIX}/auth/me")
def me(request: Request, authorization: str = Header(None)):
    _check_rate(_client_ip(request))
    with closing(db()) as conn, conn:
        user_id, username, _ = _current_user(conn, authorization)
    return {"status": "ok", "user": {"id": user_id, "username": username}}


# ───────────────────────── 加密同步 ─────────────────────────

class SyncPayload(BaseModel):
    """base_version 为客户端合并时依据的服务器版本；与当前版本不一致返回 409，客户端需重新拉取合并"""
    base_version: int = Field(..., ge=0)
    blob: str = Field(..., min_length=1, max_length=MAX_BLOB)


@app.get(f"{PREFIX}/sync")
def get_sync(request: Request, authorization: str = Header(None)):
    _check_rate(_client_ip(request))
    with closing(db()) as conn, conn:
        user_id = _current_user(conn, authorization)[0]
        row = conn.execute("SELECT version, blob, updated_at FROM sync_blobs WHERE user_id = ?", (user_id,)).fetchone()
    if not row:
        return {"status": "ok", "version": 0, "blob": None, "updated_at": 0}
    return {"status": "ok", "version": row[0], "blob": row[1], "updated_at": row[2]}


@app.put(f"{PREFIX}/sync")
def put_sync(payload: SyncPayload, request: Request, authorization: str = Header(None)):
    _check_rate(_client_ip(request))
    now_ms = int(time.time() * 1000)
    with _lock, closing(db()) as conn, conn:
        user_id = _current_user(conn, authorization)[0]
        row = conn.execute("SELECT version FROM sync_blobs WHERE user_id = ?", (user_id,)).fetchone()
        current = row[0] if row else 0
        if payload.base_version != current:
            raise HTTPException(status_code=409, detail="云端已有更新，请重新同步")
        new_version = current + 1
        conn.execute(
            "INSERT INTO sync_blobs (user_id, version, blob, updated_at) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(user_id) DO UPDATE SET version = excluded.version, blob = excluded.blob, updated_at = excluded.updated_at",
            (user_id, new_version, payload.blob, now_ms),
        )
    return {"status": "ok", "version": new_version, "updated_at": now_ms}


# ───────────────────────── 照片（密文文件） ─────────────────────────

def _user_photo_dir(user_id: int) -> str:
    return os.path.join(PHOTO_DIR, str(int(user_id)))


def _photo_path(user_id: int, photo_id: str) -> str:
    return os.path.join(_user_photo_dir(user_id), f"{photo_id}.bin")


def _check_photo_id(photo_id: str) -> None:
    if not PHOTO_ID_RE.match(photo_id):
        raise HTTPException(status_code=400, detail="照片编号不正确")


def _list_photo_ids(user_id: int) -> list[str]:
    folder = _user_photo_dir(user_id)
    try:
        names = os.listdir(folder)
    except FileNotFoundError:
        return []
    return sorted(m.group(1) for m in map(PHOTO_FILE_RE.match, names) if m)


def _store_photo(user_id: int, photo_id: str, data: bytes) -> None:
    with _lock:
        path = _photo_path(user_id, photo_id)
        if not os.path.exists(path) and len(_list_photo_ids(user_id)) >= MAX_PHOTOS_PER_USER:
            raise HTTPException(status_code=409, detail=f"云端照片已达上限 {MAX_PHOTOS_PER_USER} 张，请先删除一些")
        folder = _user_photo_dir(user_id)
        os.makedirs(folder, exist_ok=True)
        # 先写同目录临时文件再 rename，保证读到的永远是完整文件
        fd, tmp = tempfile.mkstemp(prefix=".tmp-", suffix=".part", dir=folder)
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(data)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, path)
        except BaseException:
            try:
                os.unlink(tmp)
            except FileNotFoundError:
                pass
            raise


def _read_photo(user_id: int, photo_id: str) -> bytes:
    try:
        with open(_photo_path(user_id, photo_id), "rb") as f:
            return f.read()
    except FileNotFoundError:
        raise HTTPException(status_code=404, detail="照片不存在")


def _delete_photo(user_id: int, photo_id: str) -> None:
    with _lock:
        try:
            os.unlink(_photo_path(user_id, photo_id))
        except FileNotFoundError:
            pass


@app.get(f"{PREFIX}/photos")
def list_photos(request: Request, authorization: str = Header(None)):
    _check_rate(_client_ip(request))
    user_id = _auth_user(authorization)
    return {"status": "ok", "ids": _list_photo_ids(user_id)}


@app.put(f"{PREFIX}/photos/{{photo_id}}")
async def put_photo(photo_id: str, request: Request, authorization: str = Header(None)):
    _check_rate(_client_ip(request))
    # 先鉴权、校验 id 与声明长度，再读请求体，避免未登录者白白推上来 4MB
    user_id = await run_in_threadpool(_auth_user, authorization)
    _check_photo_id(photo_id)
    declared = request.headers.get("content-length")
    if declared is not None and declared.isdigit() and int(declared) > MAX_PHOTO:
        raise HTTPException(status_code=413, detail="照片超过 4MB")
    data = await request.body()   # 总量已被 BodyLimitMiddleware 限制在 MAX_REQUEST 内
    if len(data) > MAX_PHOTO:
        raise HTTPException(status_code=413, detail="照片超过 4MB")
    if not data:
        raise HTTPException(status_code=400, detail="照片内容为空")
    await run_in_threadpool(_store_photo, user_id, photo_id, data)
    return {"status": "ok"}


@app.get(f"{PREFIX}/photos/{{photo_id}}")
def get_photo(photo_id: str, request: Request, authorization: str = Header(None)):
    _check_rate(_client_ip(request))
    user_id = _auth_user(authorization)
    _check_photo_id(photo_id)
    data = _read_photo(user_id, photo_id)
    return Response(content=data, media_type="application/octet-stream",
                    headers={"Cache-Control": "private, no-cache"})


@app.delete(f"{PREFIX}/photos/{{photo_id}}")
def delete_photo(photo_id: str, request: Request, authorization: str = Header(None)):
    _check_rate(_client_ip(request))
    user_id = _auth_user(authorization)
    _check_photo_id(photo_id)
    _delete_photo(user_id, photo_id)
    return {"status": "ok"}
