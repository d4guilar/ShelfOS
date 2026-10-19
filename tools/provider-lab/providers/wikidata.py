"""Wikidata via the Wikidata Query Service (read-only SPARQL, bounded to one ISBN per query).

Endpoint https://query.wikidata.org/sparql, GET, format=json. P212 = ISBN-13, P957 = ISBN-10,
P1476 title, P629 edition of, P123 publisher, P407 language (P218 = ISO 639-1 code), P577 date,
P179 series (P1545 ordinal qualifier), P50 author, P18 image. Values are stored as free strings so
we match the plain digits and every hyphenation split (lab/isbn.hyphen_variants)."""
import json
import urllib.parse

from lab import isbn as I
from lab.model import Normalized

HOST = "query.wikidata.org"
BASE = "https://query.wikidata.org/sparql"


def build_query(isbn13):
    vals = list(I.hyphen_variants(isbn13))
    i10 = I.to10(isbn13)
    if i10:
        vals += I.hyphen_variants(i10)
    values = " ".join('"%s"' % v for v in dict.fromkeys(vals))
    return """SELECT ?item ?isbn ?title ?work ?pubLabel ?langCode ?date ?series ?seriesLabel ?ord ?image ?author ?authorLabel ?workLabel ?workSeriesLabel ?type ?typeLabel ?ol ?gr WHERE {
  VALUES ?isbn { %s }
  { ?item wdt:P212 ?isbn } UNION { ?item wdt:P957 ?isbn }
  OPTIONAL { ?item wdt:P1476 ?title }
  OPTIONAL { ?item wdt:P629 ?work . OPTIONAL { ?work wdt:P50 ?author } OPTIONAL { ?work wdt:P179 ?workSeries } }
  OPTIONAL { ?item wdt:P123 ?pub }
  OPTIONAL { ?item wdt:P407 ?lang . ?lang wdt:P218 ?langCode }
  OPTIONAL { ?item wdt:P577 ?date }
  OPTIONAL { ?item p:P179 ?ss . ?ss ps:P179 ?series . OPTIONAL { ?ss pq:P1545 ?ord } }
  OPTIONAL { ?item wdt:P18 ?image }
  OPTIONAL { ?item wdt:P31 ?type }
  OPTIONAL { ?item wdt:P648 ?ol }
  OPTIONAL { ?item wdt:P2969 ?gr }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en,ja,es,mul". }
} LIMIT 60""" % values


def url(isbn13):
    return BASE + "?" + urllib.parse.urlencode({"query": build_query(isbn13), "format": "json"})


def _v(b, k):
    x = b.get(k)
    return x["value"] if x else None


def parse(isbn13, body):
    """Parse one SPARQL JSON response into a Normalized (several rows merge per item)."""
    n = Normalized("wikidata", isbn13)
    try:
        rows = json.loads(body)["results"]["bindings"]
    except (ValueError, KeyError, TypeError) as e:
        n.status, n.error = "REQUEST_FAILED", "malformed response: %s" % e
        return n
    if not rows:
        n.status = "NO_RESULT"
        return n
    items = {}
    for b in rows:
        q = (_v(b, "item") or "").rsplit("/", 1)[-1]
        if not q:
            continue
        it = items.setdefault(q, dict(isbns=set(), titles=[], pubs=set(), langs=set(), dates=set(), series=set(), ords=set(),
                                      images=set(), authors=set(), types=set(), ol=set(), gr=set(), work=set(), wseries=set()))
        for k, f in (("isbn", "isbns"), ("title", "titles"), ("pubLabel", "pubs"), ("langCode", "langs"), ("date", "dates"), ("seriesLabel", "series"),
                     ("ord", "ords"), ("image", "images"), ("authorLabel", "authors"), ("typeLabel", "types"), ("ol", "ol"), ("gr", "gr"),
                     ("work", "work"), ("workSeriesLabel", "wseries")):
            v = _v(b, k)
            if v is not None:
                (it[f].append(v) if isinstance(it[f], list) else it[f].add(v))
    n.candidates = len(items)
    q, it = sorted(items.items())[0]
    n.record_id = "wd:" + q
    n.entity_origin = "wd"
    n.returned_isbns = sorted({I.to13(x) or x for x in it["isbns"]})
    n.title = it["titles"][0] if it["titles"] else None
    n.creators = sorted(it["authors"])
    n.publisher = sorted(it["pubs"])[0] if it["pubs"] else None
    n.language = sorted(it["langs"])[0] if it["langs"] else None
    n.series = sorted(it["series"])[0] if it["series"] else None
    n.volume = sorted(it["ords"])[0] if it["ords"] else None
    n.published = sorted(it["dates"])[0][:10] if it["dates"] else None
    n.kind = "edition" if it["work"] else ("other" if it["types"] else None)
    n.edition_type = sorted(it["types"])[0] if it["types"] else None
    n.cover_present = bool(it["images"])
    n.cover_origin = "wikimedia-commons" if it["images"] else None
    if it["ol"]:
        n.links["openlibrary"] = sorted(it["ol"])[0]
    if it["gr"]:
        n.links["goodreads"] = sorted(it["gr"])[0]
    return n
