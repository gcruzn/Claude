"""Autenticación por contraseña con tokens de sesión y freno a fuerza bruta."""

import hmac
import secrets
import time

MAX_FAILURES = 5
LOCKOUT_SECONDS = 300
TOKEN_TTL_SECONDS = 12 * 3600


class Auth:
    def __init__(self, password, clock=time.monotonic):
        if not password or len(password) < 8:
            raise ValueError("la contraseña debe tener al menos 8 caracteres")
        self._password = password.encode()
        self._clock = clock
        self._tokens = {}
        self._failures = {}

    def is_locked(self, client):
        count, since = self._failures.get(client, (0, 0.0))
        if count < MAX_FAILURES:
            return False
        if self._clock() - since >= LOCKOUT_SECONDS:
            del self._failures[client]
            return False
        return True

    def login(self, client, password):
        """Devuelve un token si la contraseña es correcta, si no None."""
        if self.is_locked(client):
            return None
        if hmac.compare_digest(self._password, (password or "").encode()):
            self._failures.pop(client, None)
            token = secrets.token_urlsafe(32)
            self._tokens[token] = self._clock() + TOKEN_TTL_SECONDS
            return token
        count, _ = self._failures.get(client, (0, 0.0))
        self._failures[client] = (count + 1, self._clock())
        return None

    def check(self, token):
        expires = self._tokens.get(token or "")
        if expires is None:
            return False
        if self._clock() >= expires:
            del self._tokens[token]
            return False
        return True

    def logout(self, token):
        self._tokens.pop(token, None)
