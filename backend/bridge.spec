# bridge.spec — PyInstaller build spec for ShadowScript OCR backend
# ============================================================
# Build command (run from ShadowScript/backend/):
#
#   pyinstaller bridge.spec
#
# Output: backend/dist/bridge.exe
#
# What gets bundled:
#   • bridge.py + all pip dependencies (fastapi, uvicorn, easyocr, cv2, etc.)
#   • Full Tesseract-OCR v5.5.0 binary + all DLLs + tessdata language models
#   • Python runtime (handled automatically by PyInstaller)
# ============================================================

import os
from PyInstaller.utils.hooks import collect_data_files, collect_submodules

# ── Paths ─────────────────────────────────────────────────────────────────────
TESSERACT_DIR = r"C:\Program Files\Tesseract-OCR"
TESSDATA_DIR  = os.path.join(TESSERACT_DIR, "tessdata")

# ── Collect all data files needed by pip packages ─────────────────────────────
datas = []

# easyocr ships model config JSON files that must travel with the binary
datas += collect_data_files("easyocr")

# supervisely / torch model files referenced by easyocr
datas += collect_data_files("supervisely_lib", ignore_package_data=False) \
         if __import__("importlib.util", fromlist=["find_spec"]).find_spec("supervisely_lib") \
         else []

# pytesseract ships a config file it reads at runtime
datas += collect_data_files("pytesseract")

# ── Bundle Tesseract binary + every DLL in its directory ─────────────────────
# PyInstaller datas entries are (source_path_or_glob, dest_folder_in_bundle)
tesseract_binaries = []

# tesseract.exe itself → lands at the root of the bundle
tesseract_binaries.append(
    (os.path.join(TESSERACT_DIR, "tesseract.exe"), ".")
)

# Every DLL in the Tesseract install dir (leptonica, opencv, etc.)
for fname in os.listdir(TESSERACT_DIR):
    if fname.lower().endswith(".dll"):
        tesseract_binaries.append(
            (os.path.join(TESSERACT_DIR, fname), ".")
        )

# tessdata folder — contains .traineddata language models (eng.traineddata etc.)
# Walk the entire folder so sub-dirs (osd, etc.) are included too.
for root, dirs, files in os.walk(TESSDATA_DIR):
    for fname in files:
        src  = os.path.join(root, fname)
        # Preserve relative path inside tessdata/
        rel  = os.path.relpath(root, TESSERACT_DIR)   # e.g. "tessdata"
        dest = rel                                      # mirrors layout in bundle
        tesseract_binaries.append((src, dest))

# ── Hidden imports ────────────────────────────────────────────────────────────
# PyInstaller's static analysis misses dynamically imported modules.
hidden_imports = [
    # uvicorn internals
    "uvicorn.logging",
    "uvicorn.loops",
    "uvicorn.loops.auto",
    "uvicorn.loops.asyncio",
    "uvicorn.protocols",
    "uvicorn.protocols.http",
    "uvicorn.protocols.http.auto",
    "uvicorn.protocols.http.h11_impl",
    "uvicorn.protocols.websockets",
    "uvicorn.protocols.websockets.auto",
    "uvicorn.lifespan",
    "uvicorn.lifespan.on",
    # fastapi / starlette
    "fastapi",
    "starlette.routing",
    "starlette.middleware",
    "starlette.responses",
    # pydantic v2 core
    "pydantic",
    "pydantic.deprecated.class_validators",
    # easyocr
    "easyocr",
    "easyocr.easyocr",
    # image / cv2
    "cv2",
    "PIL",
    "PIL.Image",
    # numpy
    "numpy",
    # pytesseract
    "pytesseract",
    # spellchecker (optional — included so the binary works if installed)
    "spellchecker",
    # difflib / base64 / re are stdlib but list them to be safe
    "difflib",
    "base64",
    "re",
    "logging",
    "contextlib",
]

# Also pull in all uvicorn sub-modules automatically
hidden_imports += collect_submodules("uvicorn")
hidden_imports += collect_submodules("fastapi")
hidden_imports += collect_submodules("starlette")

# ── Analysis ──────────────────────────────────────────────────────────────────
a = Analysis(
    ["bridge.py"],                     # entry-point script
    pathex=["."],                      # search path — the backend/ directory
    binaries=tesseract_binaries,       # Tesseract exe + DLLs + tessdata
    datas=datas,                       # pip package data files
    hiddenimports=hidden_imports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        # Exclude heavy ML frameworks not used by this backend
        "torch",
        "torchvision",
        "tensorflow",
        "keras",
        "matplotlib",
        "scipy",
        "sklearn",
        "pandas",
        "IPython",
        "notebook",
        "jupyter",
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=None,
    noarchive=False,
)

# ── PYZ (compressed Python bytecode archive) ──────────────────────────────────
pyz = PYZ(a.pure, a.zipped_data, cipher=None)

# ── EXE ───────────────────────────────────────────────────────────────────────
exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="bridge",            # output filename: bridge.exe
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,                 # compress with UPX if available (reduces size)
    upx_exclude=[
        # DLLs that UPX is known to corrupt — exclude them for safety
        "vcruntime140.dll",
        "python3*.dll",
    ],
    runtime_tmpdir=None,
    console=True,             # keep console window so uvicorn logs are visible
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    # Output lands in backend/dist/bridge.exe
    # (PyInstaller default when run from backend/)
)
