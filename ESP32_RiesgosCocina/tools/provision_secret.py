"""PlatformIO pre-build: per-project key, never added to the distributable ZIP.
Run a second time without deleting secrets.h: keep the same secret.
"""
from pathlib import Path
import secrets
from SCons.Script import Import
Import("env")
root = Path(env.subst("$PROJECT_DIR"))
secret_path = root / "include" / "secrets.h"
if not secret_path.exists():
    secret_path.parent.mkdir(parents=True, exist_ok=True)
    key = secrets.token_hex(32)
    secret_path.write_text('#pragma once\n#define APP_SHARED_SECRET "' + key + '"\n', encoding="ascii")
    print("HMAC: nueva clave local generada en include/secrets.h; NO subir a GitHub")
else:
    print("HMAC: utilizando clave local existente; NO subir a GitHub")
