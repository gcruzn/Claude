import pytest

from remote_desktop.auth import LOCKOUT_SECONDS, MAX_FAILURES, TOKEN_TTL_SECONDS, Auth
from remote_desktop.capture import encode_jpeg
from remote_desktop.config import StateStore
from remote_desktop.desktop import friendly_app, phone_window_size
from remote_desktop.protocol import ProtocolError, parse_event, to_screen


def test_move_clamps_coordinates():
    assert parse_event({"t": "move", "x": 1.5, "y": -2}) == {"t": "move", "x": 1.0, "y": 0.0}


@pytest.mark.parametrize("bad", [
    {"t": "move", "x": "0.5", "y": 0},
    {"t": "move", "x": True, "y": 0},
    {"t": "click", "b": "turbo"},
    {"t": "click", "n": 3},
    {"t": "text", "s": ""},
    {"t": "text", "s": "x" * 1001},
    {"t": "key", "k": "rm -rf"},
    {"t": "combo", "k": ["c", "ctrl"]},
    {"t": "combo", "k": ["ctrl"]},
    {"t": "nope"},
    [],
])
def test_rejects_invalid_messages(bad):
    with pytest.raises(ProtocolError):
        parse_event(bad)


def test_valid_events():
    assert parse_event({"t": "click"}) == {"t": "click", "b": "left", "n": 1}
    assert parse_event({"t": "key", "k": "Enter"}) == {"t": "key", "k": "enter"}
    assert parse_event({"t": "combo", "k": ["ctrl", "alt", "t"]}) == {
        "t": "combo", "k": ["ctrl", "alt", "t"]}
    assert parse_event({"t": "scroll", "dy": 999}) == {"t": "scroll", "dx": 0, "dy": 50}
    assert parse_event({"t": "settings", "quality": 5, "fps": 100}) == {
        "t": "settings", "quality": 10, "fps": 30}
    assert parse_event({"t": "ack"}) == {"t": "ack"}


def test_to_screen_uses_region_offset():
    region = {"left": 1920, "top": 0, "width": 1920, "height": 1080}
    assert to_screen(0, 0, region) == (1920, 0)
    assert to_screen(1, 1, region) == (3839, 1079)


def test_encode_jpeg_downscales():
    jpeg = encode_jpeg(b"\x80" * (200 * 100 * 3), (200, 100), max_width=50, quality=50)
    assert jpeg.startswith(b"\xff\xd8")
    from io import BytesIO
    from PIL import Image
    assert Image.open(BytesIO(jpeg)).size == (50, 25)


def test_phone_window_size_is_portrait_and_fits_monitor():
    w, h = phone_window_size(390, 700, 1920, 1080)
    assert (w, h) == (448, 804)
    w, h = phone_window_size(390, 700, 1366, 768)
    assert h == 708 and w < 1366
    assert phone_window_size(300, 600, 1920, 1080)[0] == 420  # ancho mínimo


def test_friendly_app_names():
    assert friendly_app(r"C:\Program Files\Microsoft Office\EXCEL.EXE") == "Excel"
    assert friendly_app("/usr/bin/gimp") == "Gimp"
    assert friendly_app("") == "Programa"


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


def test_auth_requires_long_password():
    with pytest.raises(ValueError):
        Auth("corta")


def test_auth_login_and_token_expiry():
    clock = Clock()
    auth = Auth("clave-segura-123", clock=clock)
    assert auth.login("ip", "mala") is None
    token = auth.login("ip", "clave-segura-123")
    assert auth.check(token)
    assert not auth.check("inventado")
    clock.now += TOKEN_TTL_SECONDS
    assert not auth.check(token)


def test_auth_lockout_after_failures():
    clock = Clock()
    auth = Auth("clave-segura-123", clock=clock)
    for _ in range(MAX_FAILURES):
        auth.login("atacante", "x")
    assert auth.is_locked("atacante")
    assert auth.login("atacante", "clave-segura-123") is None
    assert auth.login("otro", "clave-segura-123") is not None
    clock.now += LOCKOUT_SECONDS
    assert auth.login("atacante", "clave-segura-123") is not None


def test_paired_phone_survives_server_restart(tmp_path):
    store = StateStore(tmp_path / "state.json")
    token = Auth("clave-segura-123", store).login("ip", "clave-segura-123")
    restarted = Auth("clave-segura-123", StateStore(tmp_path / "state.json"))
    assert restarted.check(token)
    assert token not in (tmp_path / "state.json").read_text()
    restarted.logout(token)
    assert not Auth("clave-segura-123", StateStore(tmp_path / "state.json")).check(token)


def test_revoke_all(tmp_path):
    auth = Auth("clave-segura-123", StateStore(tmp_path / "state.json"))
    token = auth.login("ip", "clave-segura-123")
    auth.revoke_all()
    assert not auth.check(token)
