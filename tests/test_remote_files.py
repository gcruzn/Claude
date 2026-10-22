import json

import pytest

from remote_desktop.config import load_config
from remote_desktop.files import FileAccessError, FileStore, safe_filename


@pytest.fixture
def store(tmp_path):
    desk = tmp_path / "Escritorio"
    (desk / "sub").mkdir(parents=True)
    (desk / "notas.txt").write_text("hola")
    (desk / "desktop.ini").write_text("x")
    (desk / ".oculto").write_text("x")
    (tmp_path / "secreto.txt").write_text("no")
    return FileStore({"Escritorio": desk})


def test_lists_folder_dirs_first_and_hides_system_files(store):
    names = [e["name"] for e in store.list("Escritorio")]
    assert names == ["sub", "notas.txt"]


@pytest.mark.parametrize("root, rel", [
    ("Escritorio", "../secreto.txt"),
    ("Escritorio", "sub/../../secreto.txt"),
    ("Escritorio", "..\\secreto.txt"),
    ("Documentos", ""),
    ("Escritorio", "no-existe.txt"),
])
def test_blocks_paths_outside_allowed_folders(store, root, rel):
    with pytest.raises(FileAccessError):
        store.resolve(root, rel)


def test_blocks_symlink_escape(store, tmp_path):
    link = store.roots["Escritorio"] / "atajo"
    try:
        link.symlink_to(tmp_path / "secreto.txt")
    except OSError:
        pytest.skip("sin permisos para symlinks")
    with pytest.raises(FileAccessError):
        store.file("Escritorio", "atajo")


def test_upload_target_never_overwrites(store):
    target = store.upload_target("Escritorio", "", "notas.txt")
    assert target.name == "notas (1).txt"


@pytest.mark.parametrize("raw, clean", [
    ("../../evil.exe", "evil.exe"),
    ('a<b>:"c".txt', "a_b___c_.txt"),
    ("CON.txt", "archivo_CON.txt"),
    ("...", "archivo_"),
])
def test_safe_filename(raw, clean):
    assert safe_filename(raw) == clean


def test_load_config_creates_default(tmp_path):
    path = tmp_path / "MiPC" / "config.json"
    config = load_config(path)
    data = json.loads(path.read_text(encoding="utf-8"))
    assert set(data["folders"]) == {"Escritorio", "Descargas"}
    assert data["projects"] == {}
    assert set(config["folders"]) == {"Escritorio", "Descargas"}


def test_load_config_rejects_bad_shape(tmp_path):
    path = tmp_path / "config.json"
    path.write_text('{"folders": ["Escritorio"], "projects": {}}')
    with pytest.raises(ValueError):
        load_config(path)
