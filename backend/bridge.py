import base64
import difflib
import logging
import re
from contextlib import asynccontextmanager

import cv2
import numpy as np
import pytesseract
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

# --- CRITICAL WINDOWS FIX START ---
# This tells Python exactly where your Tesseract "Eyes" are installed
pytesseract.pytesseract.tesseract_cmd = r'C:\Program Files\Tesseract-OCR\tesseract.exe'
# --- CRITICAL WINDOWS FIX END ---

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("shadowscript")

USE_EASYOCR = False
easy_reader = None

# ---------------------------------------------------------------------------
# Spell-checker (optional — degrades gracefully if not installed)
# Install with: pip install pyspellchecker
# ---------------------------------------------------------------------------
try:
    from spellchecker import SpellChecker
    _spell = SpellChecker()
    SPELLCHECK_AVAILABLE = True
    log.info("pyspellchecker loaded — spell correction enabled.")
except ImportError:
    _spell = None
    SPELLCHECK_AVAILABLE = False
    log.warning("pyspellchecker not installed — skipping spell correction. "
                "Run: pip install pyspellchecker")

@asynccontextmanager
async def lifespan(app: FastAPI):
    global easy_reader
    if USE_EASYOCR:
        import easyocr
        easy_reader = easyocr.Reader(["en"], gpu=False)
        log.info("EasyOCR ready.")
    else:
        log.info("Using Tesseract engine.")
    yield

app = FastAPI(title="ShadowScript OCR Bridge", version="1.0.0", lifespan=lifespan)

last_extracted_text: str = ""
SIMILARITY_THRESHOLD: float = 0.70

class FramePayload(BaseModel):
    image: str

class OCRResult(BaseModel):
    text: str
    duplicate: bool = False

def decode_base64_to_cv2(b64_string: str) -> np.ndarray:
    try:
        raw_bytes = base64.b64decode(b64_string)
        np_array  = np.frombuffer(raw_bytes, dtype=np.uint8)
        image     = cv2.imdecode(np_array, cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("Invalid image bytes.")
        return image
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Image decode failed: {e}")

def crop_caption_area(image: np.ndarray) -> np.ndarray:
    """Crop to caption band: 60%-95% vertically, skipping title bar and controls."""
    h, w = image.shape[:2]
    top    = int(h * 0.80)
    bottom = int(h * 0.96)
    return image[top:bottom, 0:w]

def preprocess(image: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape

    # 3x upscale for caption strips
    gray = cv2.resize(gray, (w * 3, h * 3), interpolation=cv2.INTER_CUBIC)

    # Stronger denoise
    gray = cv2.GaussianBlur(gray, (5, 5), 0)

    # Otsu threshold
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)

    # Remove small noise blobs (not text)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (2, 2))
    binary = cv2.morphologyEx(binary, cv2.MORPH_OPEN, kernel)

    # White padding
    binary = cv2.copyMakeBorder(binary, 30, 30, 30, 30,
                                cv2.BORDER_CONSTANT, value=255)
    return binary

def run_tesseract(image: np.ndarray) -> str:
    # PSM 6 + char blacklist filters junk symbols while reading full caption block
    config = "--oem 3 --psm 6 -c tessedit_char_blacklist=|}{]["
    return pytesseract.image_to_string(image, config=config).strip()

def run_easyocr(image: np.ndarray) -> str:
    results = easy_reader.readtext(image, detail=0, paragraph=True)
    return " ".join(results).strip()

def extract_text(preprocessed: np.ndarray) -> str:
    return run_easyocr(preprocessed) if USE_EASYOCR else run_tesseract(preprocessed)


def fix_ocr_artifacts(text: str) -> str:
    r"""
    Correct common OCR glyph-level misreads line by line.

    Glyph rules (order matters):
      1a. !' / /' / l' -> I'        contractions  (I'm, I'll, I've …)
      1b. !x / /x      -> Ix        where x is a lowercase letter
      1c. x!           -> x I       ! stuck to end of a normal word (if! -> if I)
      1d. standalone ! / / / l -> I word surrounded by spaces or boundaries
      2.  Collapse multiple spaces to one.
      3.  Insert space after commas missing one (,\S -> , \S).
      4.  CamelJoin split: lowercase immediately followed by uppercase -> insert space.

    Manual word corrections applied before spellcheck:
      Fixes words that the statistical spellchecker maps to the wrong target.
    """
    # ── Manual corrections: OCR misreads that spellcheck gets wrong ───────────
    # Keys are lowercase; we restore capitalisation after.
    _MANUAL = {
        'stuay':    'study',
        'stuaying': 'studying',
        'stuayed':  'studied',
        'stuays':   'studies',
        'mede':     'made',
        'heve':     'have',
        'wes':      'was',
        'beon':     'been',
        'thet':     'that',
        'whot':     'what',
        'ond':      'and',
        'thon':     'than',
        'yeur':     'your',
        'yoar':     'your',
    }

    lines_in  = text.split('\n')
    lines_out = []

    for line in lines_in:

        # --- Rule 1a: contraction glyph -> I' ----------------------------
        # Handles !'  /'  l'  all representing I' (I'm, I'll, I've …)
        line = re.sub(r"[!/l]'", "I'", line)

        # --- Rule 1b: glyph + lowercase letter -> I + letter -------------
        # !t -> It,  /f -> If,  !n -> In  (but NOT l + letter inside words)
        line = re.sub(r'[!/]([a-z])', lambda m: 'I' + m.group(1), line)

        # --- Rule 1c: ! or / stuck to END of a normal word -> ' I' ------
        # "if!" -> "if I",  "and/" -> "and I"
        line = re.sub(r'(?<=[a-zA-Z])[!/](?=\s|$)', ' I', line)

        # --- Rule 1d: standalone ! / / / l (word boundaries) -> I -------
        # Preceded by start-of-string or whitespace; followed by space or end.
        line = re.sub(r'(?:^|(?<=\s))[!/l](?=\s|$)', 'I', line)

        # --- Rule 2: collapse runs of spaces to single space -------------
        line = re.sub(r' {2,}', ' ', line).strip()

        # --- Rule 3: fix missing space after comma -----------------------
        line = re.sub(r',(?=\S)', ', ', line)

        # --- Rule 4: CamelJoined words -> insert space ------------------
        # "andI'm" -> "and I'm"   (lowercase immediately before uppercase)
        line = re.sub(r'(?<=[a-z])(?=[A-Z])', ' ', line)

        # --- Manual word corrections (case-insensitive, preserves case) --
        tokens = line.split(' ')
        fixed_tokens = []
        for tok in tokens:
            lower = tok.lower().rstrip('.,!?;:')
            if lower in _MANUAL:
                replacement = _MANUAL[lower]
                if tok[0].isupper():
                    replacement = replacement.capitalize()
                # Reattach trailing punctuation
                trailing = tok[len(lower):]
                fixed_tokens.append(replacement + trailing)
            else:
                fixed_tokens.append(tok)
        line = ' '.join(fixed_tokens)

        lines_out.append(line)

    return '\n'.join(lines_out)



# Tokens we must NEVER alter with spellcheck:
#   - 2 chars or shorter  (too risky: 'is', 'in', 'do' get mangled)
#   - contain non-alpha   (numbers, URLs, code, punctuation)
#   - ALL-CAPS            (acronyms)
_NON_ALPHA = re.compile(r'[^a-zA-Z]')

def _should_spellcheck(token: str) -> bool:
    return (
        len(token) > 2
        and not _NON_ALPHA.search(token)
        and not token.isupper()
    )


def spellcheck_text(text: str) -> str:
    """Correct misspelled alphabetic words; leaves numbers/symbols/code alone."""
    if not SPELLCHECK_AVAILABLE:
        return text
    lines_out = []
    for line in text.split('\n'):
        tokens = line.split(' ')
        fixed = []
        for tok in tokens:
            if not _should_spellcheck(tok):
                fixed.append(tok)
                continue
            lower = tok.lower()
            correction = _spell.correction(lower)
            if correction and correction != lower:
                # Mirror original capitalisation
                correction = correction.capitalize() if tok[0].isupper() else correction
                log.debug(f"[SPELL] {tok!r} -> {correction!r}")
                fixed.append(correction)
            else:
                fixed.append(tok)
        lines_out.append(' '.join(fixed))
    return '\n'.join(lines_out)

def clean_text(text: str) -> str:
    lines = text.split('\n')
    # Drop lines shorter than 4 chars (noise / stray glyphs)
    lines = [l for l in lines if len(l.strip()) >= 7]
    # Drop lines where more than 35% of the chars are non-alphanumeric
    lines = [l for l in lines
             if len(re.sub(r'[^a-zA-Z0-9\s]', '', l)) > len(l) * 0.65]
    return '\n'.join(lines).strip()

def is_duplicate(new_text: str) -> bool:
    global last_extracted_text
    if not last_extracted_text:
        return False
    ratio = difflib.SequenceMatcher(None, last_extracted_text.lower(), new_text.lower()).ratio()
    return ratio >= SIMILARITY_THRESHOLD

@app.post("/process-frame")
async def process_frame(payload: FramePayload):
    global last_extracted_text
    image        = decode_base64_to_cv2(payload.image)
    image        = crop_caption_area(image)
    preprocessed = preprocess(image)
    raw_text     = extract_text(preprocessed)
    raw_text     = fix_ocr_artifacts(raw_text)  # ! -> I, spacing fixes
    raw_text     = spellcheck_text(raw_text)     # stuay -> study, etc.
    raw_text     = clean_text(raw_text)
    if not raw_text:
        return JSONResponse(content={"text": "", "duplicate": False})
    if is_duplicate(raw_text):
        log.info(f"Duplicate detected: {repr(raw_text[:30])}...")
        return JSONResponse(content={"text": "", "duplicate": True})
    last_extracted_text = raw_text
    log.info(f"OCR Output: {repr(raw_text)}")
    return JSONResponse(content={"text": raw_text, "duplicate": False})

@app.get("/health")
async def health():
    return {"status": "ok", "engine": "easyocr" if USE_EASYOCR else "tesseract"}

if __name__ == "__main__":
    import uvicorn
    # We use 127.0.0.1 (localhost) so your Java app can find it easily
    uvicorn.run(app, host="127.0.0.1", port=8000)
    