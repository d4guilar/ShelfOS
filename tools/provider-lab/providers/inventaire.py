"""Inventaire via the documented read-only GET /api/entities/by-uris (OpenAPI spec 5.1.1,
https://inventaire.io/public/api_specs.json). `uris` accepts "isbn:<isbn>" pseudo URIs and several
URIs separated by '|'. No `refresh`, no POST/PUT, no authentication, no image fetch.

Edition claims used: P212/P957 ISBN, P1476 title, P629 edition of (work), P123 publisher, P407
language, P577 date, P179/P1545 series/ordinal, P648 Open Library id, P2969 Goodreads id,
invp:P2 image hash. Work claims: P50 author. The `inv:`/`wd:` prefix tells whether the record is
Inventaire's own or Wikidata-backed."""
import json
import urllib.parse

from lab import isbn as I
from lab.model import Normalized

HOST = "inventaire.io"
BASE = "https://inventaire.io/api/entities/by-uris"
LANG = {"wd:Q1860": "en", "wd:Q5287": "ja", "wd:Q1321": "es", "wd:Q150": "fr", "wd:Q188": "de", "wd:Q7737": "ru", "wd:Q652": "it"}


def url(uris, attributes=None):
    q = {"uris": "|".join(uris)}
    if attributes:
        q["attributes"] = "|".join(attributes)
    return BASE + "?" + urllib.parse.urlencode(q, safe="|:")


def _claims(e, p):
    return (e or {}).get("claims", {}).get(p, [])


def _first(e, p):
    v = _claims(e, p)
    return v[0] if v else None


def _label(e):
    if not e:
        return None
    labels = e.get("labels") or {}
    for k in ("en", "ja", "es", "fromclaims"):
        if labels.get(k):
            return labels[k]
    return next(iter(labels.values()), None)


def parse_entities(body):
    d = json.loads(body)
    if not isinstance(d, dict) or not isinstance(d.get("entities"), dict):
        raise ValueError("no 'entities' object")
    return d["entities"], d.get("redirects") or {}


def parse(isbn13, edition_body, related_body=None):
    """Normalize the edition response (and the optional batched work/publisher/author response)."""
    n = Normalized("inventaire", isbn13)
    try:
        ents, _ = parse_entities(edition_body)
    except (ValueError, TypeError) as e:
        n.status, n.error = "REQUEST_FAILED", "malformed response: %s" % e
        return n
    editions = {u: e for u, e in ents.items() if isinstance(e, dict) and e.get("type") == "edition"}
    if not editions:
        n.status = "NO_RESULT"
        return n
    n.candidates = len(editions)
    uri, e = sorted(editions.items())[0]
    n.record_id = e.get("uri") or uri
    n.entity_origin = "inv" if str(n.record_id).startswith("inv:") else ("wd" if str(n.record_id).startswith("wd:") else None)
    n.kind = "edition"
    n.returned_isbns = sorted({I.to13(x) or x for x in _claims(e, "wdt:P212") + _claims(e, "wdt:P957")})
    n.title = _first(e, "wdt:P1476") or (e.get("labels") or {}).get("fromclaims")
    pub_uri = _first(e, "wdt:P123")
    n.language = LANG.get(_first(e, "wdt:P407")) if _first(e, "wdt:P407") else None
    if _first(e, "wdt:P407") and n.language is None:
        n.language = _first(e, "wdt:P407")   # unmapped item id, kept as is
    n.published = _first(e, "wdt:P577")
    n.volume = str(_first(e, "wdt:P1545")) if _first(e, "wdt:P1545") is not None else None
    n.cover_present = bool(_claims(e, "invp:P2") or e.get("image"))
    if n.cover_present:
        n.cover_origin = "inventaire-hosted" if _claims(e, "invp:P2") else "external"
    for p, k in (("wdt:P648", "openlibrary"), ("wdt:P2969", "goodreads")):
        if _first(e, p):
            n.links[k] = str(_first(e, p))
    work_uri = _first(e, "wdt:P629")
    n.links["work"] = work_uri
    if related_body:
        try:
            rel, _ = parse_entities(related_body)
        except (ValueError, TypeError):
            rel = {}
        pub = rel.get(pub_uri) if pub_uri else None
        n.publisher = _label(pub)
        work = rel.get(work_uri) if work_uri else None
        if work:
            n.creators = [x for x in (_label(rel.get(a)) for a in _claims(work, "wdt:P50")) if x]
            s = _first(work, "wdt:P179")
            n.series = _label(rel.get(s)) if s else None
            if n.volume is None and _first(work, "wdt:P1545") is not None:
                n.volume = str(_first(work, "wdt:P1545"))
    return n


def related_uris(edition_body):
    """URIs worth a second batched call: work and publisher (authors/series follow from the work)."""
    ents, _ = parse_entities(edition_body)
    e = next((x for x in ents.values() if isinstance(x, dict) and x.get("type") == "edition"), None)
    return [u for u in (_first(e, "wdt:P629"), _first(e, "wdt:P123")) if u] if e else []


def authors_and_series(related_body):
    rel, _ = parse_entities(related_body)
    out = []
    for w in rel.values():
        out += _claims(w, "wdt:P50") + ([_first(w, "wdt:P179")] if _first(w, "wdt:P179") else [])
    return list(dict.fromkeys(out))


def fetch(client, isbn13):
    """Edition request, then up to two batched requests (work+publisher, then authors+series). Never raises on failure."""
    n0 = Normalized("inventaire", isbn13)
    r = client.get(url(["isbn:" + isbn13]))
    n0.requests = 1
    if not r.ok:
        n0.status, n0.error, n0.seconds = "REQUEST_FAILED", r.error or "HTTP %s" % r.status, r.elapsed
        return n0, r.body
    secs = r.elapsed
    try:
        ru = related_uris(r.body)
    except (ValueError, TypeError):
        ru = []
    rel = None
    reqs = 1
    if ru:
        r2 = client.get(url(ru, ["labels", "claims"]))
        reqs += 1
        secs += r2.elapsed
        if r2.ok:
            rel = r2.body
            try:
                more = [u for u in authors_and_series(rel) if u not in ru]
            except (ValueError, TypeError):
                more = []
            if more:
                r3 = client.get(url(more, ["labels"]))
                reqs += 1
                secs += r3.elapsed
                if r3.ok:
                    merged = json.loads(rel)
                    merged["entities"].update(json.loads(r3.body).get("entities", {}))
                    rel = json.dumps(merged).encode()
    n = parse(isbn13, r.body, rel)
    n.requests, n.seconds = reqs, secs
    return n, r.body
