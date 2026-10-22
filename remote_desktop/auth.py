"""Vinculación de celulares: contraseña una vez, después entra solo.

Al ingresar la contraseña el celular recibe un token de larga duración.
En el PC solo se guarda su hash, así que copiar state.json no permite
entrar. Tras demasiados intentos fallidos esa IP queda bloqueada un rato.
"""

import hashlib
import hmac
import secrets
import time

MAX_FAILURES = 5
LOCKOUT_SECONDS = 300
TOKEN_TTL_SECONDS = 180 * 24 * 3600
STATE_KEY = "devices"
PASSWORD_KEY = "password"
PBKDF2_ROUNDS = 200_000


def _digest(token):
    return hashlib.sha256(token.encode()).hexdigest()


def hash_password(password, salt=None):
    if not isinstance(password, str) or len(password) < 8:
        raise ValueError("la contraseña debe tener al menos 8 caracteres")
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt),
                                 PBKDF2_ROUNDS).hex()
    return {"salt": salt, "hash": digest}


class Auth:
    """password: la contraseña, o None para usar la guardada en el estado."""

    def __init__(self, password=None, store=None, clock=time.time):
        if password is not None:
            self._password = hash_password(password)
            if store:
                store.set(PASSWORD_KEY, self._password)
        else:
            self._password = store.get(PASSWORD_KEY) if store else None
            if not self._password:
                raise ValueError("no hay contraseña configurada")
        self._store = store
        self._clock = clock
        self._devices = store.get(STATE_KEY, {}) if store else {}
        self._failures = {}

    def _save(self):
        if self._store:
            self._store.set(STATE_KEY, self._devices)

    def is_locked(self, client):
        count, since = self._failures.get(client, (0, 0.0))
        if count < MAX_FAILURES:
            return False
        if self._clock() - since >= LOCKOUT_SECONDS:
            del self._failures[client]
            return False
        return True

    def login(self, client, password, device="celular"):
        """Devuelve un token si la contraseña es correcta, si no None."""
        if self.is_locked(client):
            return None
        try:
            attempt = hash_password(password, self._password["salt"])["hash"]
        except ValueError:
            attempt = ""
        if not hmac.compare_digest(self._password["hash"], attempt):
            count, _ = self._failures.get(client, (0, 0.0))
            self._failures[client] = (count + 1, self._clock())
            return None
        self._failures.pop(client, None)
        token = secrets.token_urlsafe(32)
        self._devices[_digest(token)] = {
            "name": str(device)[:60],
            "expires": self._clock() + TOKEN_TTL_SECONDS,
        }
        self._save()
        return token

    def check(self, token):
        if not token:
            return False
        key = _digest(token)
        entry = self._devices.get(key)
        if entry is None:
            return False
        if self._clock() >= entry["expires"]:
            del self._devices[key]
            self._save()
            return False
        return True

    def logout(self, token):
        if token and self._devices.pop(_digest(token), None) is not None:
            self._save()

    def revoke_all(self):
        self._devices = {}
        self._save()
