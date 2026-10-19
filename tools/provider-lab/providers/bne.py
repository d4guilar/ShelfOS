"""Biblioteca Nacional de España (BNE) via the documented SRU 1.2 server (Phase 4.5C.P1, research only).

Service (BNE librarians' "Download with SRU" page): https://catalogo.bne.es/view/sru/34BNE_INST, SRU 1.2, operations explain and
searchRetrieve, CQL, index family `alma.*` (ISBN index `alma.isbn`), schema marcxml. Read-only GET. Which indexes and schemas the server
really supports is taken from its Explain response (see parse_explain), never assumed.

MARC21 reading is conservative: unknown stays unknown, no volume is invented, a generic series title is not an edition, and every
record is kept (several records are several candidates)."""
import re
import urllib.parse
import xml.etree.ElementTree as ET

from lab import isbn as I
from lab.model import Normalized

HOST = "catalogo.bne.es"
BASE = "https://catalogo.bne.es/view/sru/34BNE_INST"
MAX_RECORDS = 5
MARC_LANG = {"spa": "es", "jpn": "ja", "eng": "en", "cat": "ca", "glg": "gl", "eus": "eu", "baq": "eu", "fre": "fr", "fra": "fr", "ger": "de", "deu": "de", "ita": "it", "por": "pt"}
MARC = "{http://www.loc.gov/MARC21/slim}"
SRW = "{http://www.loc.gov/zing/srw/}"


class ParseError(Exception):
    pass


def cql_quote(value):
    """A CQL string literal: always quoted, with backslash and double quote escaped."""
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def explain_url():
    return BASE + "?" + urllib.parse.urlencode({"version": "1.2", "operation": "explain"})


def isbn_query(isbn13):
    """alma.isbn for the ISBN-13 digits, OR the ISBN-10 when one exists (one request covers both spellings)."""
    if not I.check13(isbn13):
        raise ValueError("not a valid ISBN-13: %r" % isbn13)
    q = "alma.isbn=" + cql_quote(isbn13)
    i10 = I.to10(isbn13)
    return q + (" or alma.isbn=" + cql_quote(i10) if i10 else "")


def search_url(isbn13, maximum=MAX_RECORDS):
    maximum = max(1, min(int(maximum), 10))
    return BASE + "?" + urllib.parse.urlencode({"version": "1.2", "operation": "searchRetrieve", "query": isbn_query(isbn13), "recordSchema": "marcxml",
                                                 "maximumRecords": maximum, "startRecord": 1}, quote_via=urllib.parse.quote)


def _xml(body):
    # Standard-library parser; the response is size-capped by the HTTP client and DOCTYPE/entity declarations are refused.
    if re.search(rb"<!(DOCTYPE|ENTITY)", body[:4096], re.I):
        raise ParseError("DTD or entity declarations are refused")
    try:
        return ET.fromstring(body)
    except ET.ParseError as e:
        raise ParseError("malformed XML: %s" % e)


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def parse_explain(body):
    """-> dict(version, indexes=[names], schemas=[names]) from whatever the Explain response declares."""
    root = _xml(body)
    out = dict(version=None, indexes=[], schemas=[], root=_local(root.tag))
    for el in root.iter():
        n = _local(el.tag)
        if n == "version" and (el.text or "").strip():
            out["version"] = el.text.strip()
        elif n == "index":
            for m in el.iter():
                if _local(m.tag) in ("name", "title") and (m.text or "").strip():
                    out["indexes"].append(m.text.strip())
                    break
        elif n == "schemaInfo" or n == "schema":
            name = el.get("name") or el.get("identifier")
            if name:
                out["schemas"].append(name)
    out["indexes"] = list(dict.fromkeys(out["indexes"]))
    out["schemas"] = list(dict.fromkeys(out["schemas"]))
    return out


def parse_search(body):
    """-> dict(total, records=[marc-record Element], diagnostics=[str]); raises ParseError for non-SRU or malformed XML."""
    root = _xml(body)
    if _local(root.tag) != "searchRetrieveResponse":
        raise ParseError("not an SRU searchRetrieveResponse (root %s)" % _local(root.tag))
    total = None
    diags = []
    records = []
    for el in root.iter():
        n = _local(el.tag)
        if n == "numberOfRecords":
            try:
                total = int((el.text or "").strip())
            except ValueError:
                raise ParseError("bad numberOfRecords")
        elif n == "diagnostic":
            diags.append(" ".join((x.text or "").strip() for x in el.iter() if (x.text or "").strip()))
        elif n == "recordData":
            records.extend(c for c in el if _local(c.tag) == "record")   # MARC record, namespaced or not
    if total is None:
        raise ParseError("numberOfRecords missing")
    return dict(total=total, records=records, diagnostics=diags)


def _fields(rec, tag):
    return [f for f in rec if _local(f.tag) == "datafield" and f.get("tag") == tag]


def _sub(field, codes):
    return [(s.text or "").strip() for s in field if _local(s.tag) == "subfield" and s.get("code") in codes and (s.text or "").strip()]


def _clean(s):
    return re.sub(r"\s+", " ", re.sub(r"\s*[/:;,.=]\s*$", "", (s or "").strip())).strip() or None


def _control(rec, tag):
    for f in rec:
        if _local(f.tag) == "controlfield" and f.get("tag") == tag:
            return (f.text or "").strip()
    return None


def isbns(rec):
    """-> (valid ISBN-13s from 020$a, cancelled/invalid from 020$z). Qualifiers like '(rústica)' are dropped; ISBN-10 maps to ISBN-13."""
    good, bad = [], []
    for f in _fields(rec, "020"):
        for code, bucket in (("a", good), ("z", bad)):
            for raw in _sub(f, code):
                d = I.digits(re.sub(r"\(.*?\)|[:;].*", "", raw).strip().split(" ")[0])
                x = I.to13(d)
                bucket.append(x if x else d)
    return list(dict.fromkeys(good)), list(dict.fromkeys(bad))


_vol = re.compile(r"^(?:v\.?|vol\.?|volumen|n\.?[ºo°]?|núm\.?|num\.?|t\.?|tomo)?\s*(\d{1,4}(?:\s*[-–]\s*\d{1,4})?)\.?$", re.I)


def volume_of(text):
    """A volume only when the text is just a number (with an optional v./n.º/tomo prefix) or a range; anything else stays unknown."""
    if not text:
        return None
    m = _vol.match(text.strip())
    return re.sub(r"\s+", "", m.group(1)).replace("–", "-") if m else None


def normalize_record(isbn13, rec):
    """One MARC record -> Normalized (status OK). Fields absent from the record stay None."""
    n = Normalized("bne", isbn13)
    n.kind = "edition"
    n.record_id = _control(rec, "001")
    n.returned_isbns, cancelled = isbns(rec)
    n.links["cancelled_isbns"] = cancelled
    f245 = (_fields(rec, "245") or [None])[0]
    if f245 is not None:
        a, b, nn, p = _sub(f245, "a"), _sub(f245, "b"), _sub(f245, "n"), _sub(f245, "p")
        n.title = _clean(" ".join(a[:1] + nn[:1] + p[:1])) or None
        n.links["title_subtitle"] = _clean(" ".join(b)) if b else None
        n.links["title_part_number"] = _clean(nn[0]) if nn else None
        n.links["responsibility"] = _clean(" ".join(_sub(f245, "c")))
    creators = []
    for tag in ("100", "700"):
        for f in _fields(rec, tag):
            for nm in _sub(f, "a"):
                nm = re.sub(r"\s*,\s*$", "", nm)
                if nm and nm not in creators:
                    creators.append(nm)
    n.creators = creators
    for f in _fields(rec, "264") + _fields(rec, "260"):
        pubs, dates = _sub(f, "b"), _sub(f, "c")
        if pubs and not n.publisher:
            n.publisher = _clean(pubs[0])
        if dates and not n.published:
            m = re.search(r"\d{4}", dates[0])
            n.published = m.group(0) if m else None
    lang = None
    f041 = (_fields(rec, "041") or [None])[0]
    if f041 is not None and _sub(f041, "a"):
        lang = _sub(f041, "a")[0]
    elif _control(rec, "008") and len(_control(rec, "008")) >= 38:
        lang = _control(rec, "008")[35:38]
    if lang and lang.strip() and lang not in ("|||", "   ", "zxx", "und", "mul"):
        n.language = MARC_LANG.get(lang)
        n.links["marc_language"] = lang
    series, svol = [], None
    for tag in ("490", "830", "440"):
        for f in _fields(rec, tag):
            sa, sv = _sub(f, "a"), _sub(f, "v")
            if sa:
                series.append(_clean(sa[0]))
            if sv and svol is None:
                svol = _clean(sv[0])
    n.series = series[0] if series else None
    n.links["series_all"] = list(dict.fromkeys(x for x in series if x))
    n.links["series_volume_raw"] = svol
    # Volume: series enumeration first, then the 245 part number. Only an unambiguous number/range is accepted; otherwise unknown.
    n.volume = volume_of(svol) or volume_of(n.links.get("title_part_number"))
    ed = _fields(rec, "250")
    n.edition_type = _clean(" ".join(_sub(ed[0], "a", ))) if ed and _sub(ed[0], "a") else None
    n.cover_present = False   # a MARC record carries no cover; none is requested
    n.entity_origin = "bne"
    return n


def parse(isbn13, body):
    """Search response -> Normalized. All returned records are kept in n.links['records']; the first is the representative;
    n.candidates counts records whose own ISBN list contains the queried ISBN."""
    n = Normalized("bne", isbn13)
    try:
        res = parse_search(body)
    except ParseError as e:
        n.status, n.error = "REQUEST_FAILED", str(e)
        return n
    if res["diagnostics"]:
        n.status, n.error = "REQUEST_FAILED", "SRU diagnostic: " + "; ".join(res["diagnostics"])[:200]
        return n
    n.links["total_reported"] = res["total"]
    if res["total"] == 0 and not res["records"]:
        n.status = "NO_RESULT"
        return n
    recs = [normalize_record(isbn13, r) for r in res["records"]]
    if not recs:
        n.status, n.error = "REQUEST_FAILED", "numberOfRecords=%d but no records were returned" % res["total"]
        return n
    matching = [r for r in recs if isbn13 in r.returned_isbns]
    rep = (matching or recs)[0]
    rep.candidates = len(matching) or len(recs)
    rep.links["total_reported"] = res["total"]
    rep.links["records_returned"] = len(recs)
    rep.links["other_records"] = [dict(record_id=r.record_id, title=r.title, isbns=r.returned_isbns, volume=r.volume, edition=r.edition_type) for r in recs if r is not rep]
    rep.links["queried_isbn_in_record"] = isbn13 in rep.returned_isbns
    return rep
