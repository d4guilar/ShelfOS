"""Ground-truth comparison of a provider record with a verified fixture (NOT the ShelfOS matcher).

Classes: EXACT_EDITION, PARTIAL_EDITION, AMBIGUOUS, WRONG_EDITION, CONFLICTING_VOLUME, NO_RESULT,
REQUEST_FAILED. The lab's own title tolerance is deliberately simple; ShelfOS's accept/review/reject
decision comes only from the production MetadataMatcher via the JVM bridge (lab/bridge.py)."""
import re
import unicodedata

CLASSES = ["EXACT_EDITION", "PARTIAL_EDITION", "AMBIGUOUS", "WRONG_EDITION", "CONFLICTING_VOLUME", "NO_RESULT", "REQUEST_FAILED", "NOT_EVALUATED"]


def norm(s):
    s = unicodedata.normalize("NFKC", s or "").lower()
    return re.sub(r"\s+", " ", re.sub(r"[^\w\s]", " ", s)).strip()


def _latin(s):
    return all(ord(c) < 0x250 for c in s if c.isalpha())


def title_ok(expected, got):
    """True (same title), "fragment" (one contains the other but is much shorter, e.g. series name without
    volume/subtitle), "incomparable" (different scripts: no translation is assumed), False, or None (no title)."""
    a, b = norm(expected), norm(got)
    if not a or not b:
        return None
    if a == b:
        return True
    ta, tb = set(a.split()), set(b.split())
    if a in b or b in a:
        short, long_ = (a, b) if len(a) <= len(b) else (b, a)
        return True if len(short.split()) / len(long_.split()) >= 0.5 else "fragment"
    if len(ta & tb) / len(ta | tb) >= 0.6:
        return True
    return False if (_latin(a) and _latin(b)) else "incomparable"


def single_volume(v):
    return v if v and re.fullmatch(r"\d+(\.\d+)?", v) else None


def classify(fx, rec):
    """fx: fixture dict; rec: Normalized-like dict. Returns (class, reasons[])."""
    if rec["status"] == "REQUEST_FAILED":
        return "REQUEST_FAILED", [rec.get("error") or "request failed"]
    if rec["status"] == "NO_RESULT":
        return "NO_RESULT", []
    why = []
    if rec["returned_isbns"] and fx["isbn13"] not in rec["returned_isbns"]:
        return "WRONG_EDITION", ["returned ISBN %s != queried" % rec["returned_isbns"]]
    exp_lang, got_lang = fx.get("expected_language"), rec.get("language")
    if exp_lang and got_lang and exp_lang.split("-")[0] != got_lang.split("-")[0]:
        return "WRONG_EDITION", ["language %s != %s" % (got_lang, exp_lang)]
    t = title_ok(fx["expected_title"], rec.get("title"))
    if t is False:
        return "WRONG_EDITION", ["title '%s' vs expected '%s'" % (rec.get("title"), fx["expected_title"])]
    if t in ("fragment", "incomparable"):
        why.append("title %s: '%s' vs expected '%s'" % (t, rec.get("title"), fx["expected_title"]))
    ev, gv = single_volume(fx.get("expected_volume")), single_volume(rec.get("volume"))
    if ev and gv and float(ev) != float(gv):
        return "CONFLICTING_VOLUME", ["volume %s != %s" % (gv, ev)]
    if rec.get("candidates", 1) > 1:
        return "AMBIGUOUS", ["%d distinct editions for one ISBN" % rec["candidates"]]
    collection = fx["edition_type"] in ("omnibus", "collected")
    if collection and fx.get("expected_volume") and not ev and gv:
        return "PARTIAL_EDITION", ["collection fixture (volumes %s) but provider reports single volume %s" % (fx["expected_volume"], gv)]
    if t is None:
        why.append("no title returned")
    if ev and not gv:
        why.append("expected volume %s, none returned" % ev)
    return ("PARTIAL_EDITION" if why else "EXACT_EDITION"), why
