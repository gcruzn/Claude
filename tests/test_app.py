import pytest
from app import greet, add


def test_greet():
    assert greet("Claude") == "Hola, Claude! Bienvenido a mi primera aplicación."


def test_greet_empty_raises():
    with pytest.raises(ValueError):
        greet("")


def test_add():
    assert add(2, 3) == 5
    assert add(-1, 1) == 0
    assert add(0, 0) == 0
