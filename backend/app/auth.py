import hashlib
import hmac
import secrets
import time
from collections import deque
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from threading import RLock


@dataclass(frozen=True)
class Session:
    owner: str
    provider_id: str
    model: str
    expires_at: datetime
    api_key: str = field(repr=False)


class Sessions:
    """短期令牌与 Key 只留在内存；磁盘只保存随机身份盐。"""

    def __init__(self, data_dir: Path, hours: int = 24):
        data_dir.mkdir(parents=True, exist_ok=True)
        secret_path = data_dir / "identity.secret"
        try:
            with secret_path.open("xb") as output:
                output.write(secrets.token_bytes(32))
            secret_path.chmod(0o600)
        except FileExistsError:
            pass
        self._secret = secret_path.read_bytes()
        if len(self._secret) != 32:
            raise ValueError("身份密钥文件损坏，请从备份恢复 identity.secret")
        self._hours = hours
        self._items: dict[str, Session] = {}
        self._lock = RLock()

    def issue(self, provider_id: str, key: str, model: str) -> tuple[str, Session]:
        owner = hmac.new(self._secret, f"{provider_id}\0{key}".encode(), hashlib.sha256).hexdigest()
        session = Session(owner, provider_id, model, datetime.now(UTC) + timedelta(hours=self._hours), key)
        token = secrets.token_urlsafe(32)
        with self._lock:
            self._purge()
            if len(self._items) >= 4096:
                raise ValueError("当前在线连接过多，请稍后重试")
            self._items[self._digest(token)] = session
        return token, session

    def get(self, token: str) -> Session | None:
        with self._lock:
            self._purge()
            return self._items.get(self._digest(token))

    def revoke(self, token: str):
        with self._lock:
            self._items.pop(self._digest(token), None)

    def _purge(self):
        now = datetime.now(UTC)
        self._items = {key: item for key, item in self._items.items() if item.expires_at > now}

    @staticmethod
    def _digest(token: str) -> str:
        return hashlib.sha256(token.encode()).hexdigest()


class LoginLimiter:
    def __init__(self, limit: int = 10, window: float = 60):
        self.limit, self.window = limit, window
        self._attempts: dict[str, deque] = {}
        self._lock = RLock()

    def allow(self, address: str) -> bool:
        now = time.monotonic()
        with self._lock:
            cutoff = now - self.window
            self._attempts = {k: v for k, v in self._attempts.items() if v and v[-1] > cutoff}
            if address not in self._attempts and len(self._attempts) >= 4096:
                return False
            recent = self._attempts.setdefault(address, deque())
            while recent and recent[0] <= cutoff:
                recent.popleft()
            if len(recent) >= self.limit:
                return False
            recent.append(now)
            return True
