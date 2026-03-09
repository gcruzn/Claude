def greet(name: str) -> str:
    """Return a greeting message."""
    if not name:
        raise ValueError("Name cannot be empty")
    return f"Hola, {name}! Bienvenido a mi primera aplicación."


def add(a: int, b: int) -> int:
    """Return the sum of two numbers."""
    return a + b


if __name__ == "__main__":
    print(greet("Mundo"))
    print(f"2 + 3 = {add(2, 3)}")
