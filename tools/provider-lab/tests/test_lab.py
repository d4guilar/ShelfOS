"""Offline tests for the provider lab. No network: every response below is a small synthetic payload
shaped like the real API (written for these tests, not copied upstream data).
Run:  python -m unittest discover -s tests -t .   (from tools/provider-lab)"""
import csv
import json
import os
import sys
import tempfile
import unittest
import urllib.parse

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import benchmark                                         # noqa: E402
from lab import bridge, classify, isbn as I, report     # noqa: E402
from lab.http import Client, Blocked, UA                # noqa: E402
from providers import inventaire as V, wikidata as W    # noqa: E402

ISBN = "9781569319017"


def fake(responses):
    """Transport returning queued (status, headers, body) or raising queued exceptions; records URLs."""
    calls = []

    def t(url, headers, timeout, max_bytes):
        calls.append((url, dict(headers)))
        r = responses.pop(0)
        if isinstance(r, Exception):
            raise r
        return r
    t.calls = calls
    return t


def client(responses, host="inventaire.io", **kw):
    sleeps = []
    c = Client(host, transport=fake(responses), sleep=sleeps.append, clock=lambda: 0.0, **kw)
    c.sleeps = sleeps
    return c


class Corpus(unittest.TestCase):
    def test_committed_corpus_is_valid(self):
        errs, counts = benchmark.validate()
        self.assertEqual([], errs)
        self.assertEqual(36, sum(counts.values()))

    def test_schema_checksum_and_duplicates_are_rejected(self):
        c = benchmark.load()
        bad = json.loads(json.dumps(c))
        bad["fixtures"][0]["isbn13"] = "9780743273566"          # wrong check digit
        bad["fixtures"][1]["id"] = bad["fixtures"][0]["id"]      # duplicate id
        del bad["fixtures"][2]["expected_title"]                 # missing field
        errs, _ = benchmark.validate(bad)
        text = " ".join(errs)
        self.assertIn("checksum", text); self.assertIn("duplicate", text); self.assertIn("missing expected_title", text)

    def test_every_fixture_has_independent_https_sources_not_the_providers(self):
        for f in benchmark.load()["fixtures"]:
            self.assertTrue(f["source_urls"], f["id"])
            for u in f["source_urls"]:
                self.assertNotIn("inventaire.io", u); self.assertNotIn("wikidata.org", u)


class Isbn(unittest.TestCase):
    def test_conversion_and_checksums(self):
        self.assertEqual("9780743273565", I.to13("0-7432-7356-7"))
        self.assertEqual("0743273567", I.to10("9780743273565"))
        self.assertIsNone(I.to13("0743273568")); self.assertIsNone(I.to13("123"))
        self.assertTrue(I.check10("080442957X")); self.assertEqual("9780804429573", I.to13("080442957X"))
        self.assertIsNone(I.to10("9791234567896"))               # 979 has no ISBN-10

    def test_hyphen_variants_bounded_and_contain_the_real_spelling(self):
        v = I.hyphen_variants("9780743273565")
        self.assertIn("978-0-7432-7356-5", v); self.assertIn("9780743273565", v)
        self.assertLessEqual(len(v), 40); self.assertEqual(len(v), len(set(v)))
        self.assertIn("978-4-08-873621-1", I.hyphen_variants("9784088736211"))


class Http(unittest.TestCase):
    def test_get_only_identified_and_host_bound(self):
        c = client([(200, {}, b"{}")])
        c.get("https://inventaire.io/api/x")
        self.assertIn("github.com/d4guilar/ShelfOS", c.transport.calls[0][1]["User-Agent"])
        with self.assertRaises(ValueError):
            c.get("https://evil.example/api")
        with self.assertRaises(ValueError):
            c.get("http://inventaire.io/api")

    def test_min_interval_between_requests(self):
        t = [0.0]
        sleeps = []
        c = Client("inventaire.io", min_interval=1.0, transport=fake([(200, {}, b"{}")] * 2), sleep=sleeps.append, clock=lambda: t[0])
        c.get("https://inventaire.io/a"); c.get("https://inventaire.io/b")
        self.assertEqual([1.0], sleeps)

    def test_429_and_403_stop_the_provider_and_honour_retry_after_text(self):
        for code in (429, 403):
            c = client([(code, {"Retry-After": "30"}, b"")] + [(200, {}, b"{}")])
            with self.assertRaises(Blocked) as e:
                c.get("https://inventaire.io/a")
            self.assertIn(str(code), str(e.exception))
            if code == 429:
                self.assertIn("30", str(e.exception))
            with self.assertRaises(Blocked):
                c.get("https://inventaire.io/b")                 # stays stopped
            self.assertEqual(1, len(c.transport.calls))

    def test_transient_failure_retries_then_reports_not_missing(self):
        c = client([OSError("boom"), (503, {"Retry-After": "7"}, b""), (200, {}, b"{}")])
        r = c.get("https://inventaire.io/a")
        self.assertTrue(r.ok); self.assertEqual(3, len(c.transport.calls)); self.assertEqual(7.0, max(c.sleeps))
        c2 = client([OSError("down")] * 5, retries=1)
        r2 = c2.get("https://inventaire.io/a")
        self.assertFalse(r2.ok); self.assertIn("OSError", r2.error)    # an error, never "no result"

    def test_4xx_is_not_retried_and_oversize_is_an_error(self):
        c = client([(404, {}, b"")])
        r = c.get("https://inventaire.io/a")
        self.assertEqual(1, len(c.transport.calls)); self.assertEqual("HTTP 404", r.error)
        c2 = client([(200, {}, b"x" * 20)], max_bytes=10)
        self.assertIn("larger", c2.get("https://inventaire.io/a").error)

    def test_request_budget(self):
        c = client([(200, {}, b"{}")] * 5, max_requests=2)
        c.get("https://inventaire.io/a"); c.get("https://inventaire.io/b")
        with self.assertRaises(Blocked):
            c.get("https://inventaire.io/c")


EDITION = json.dumps({"entities": {"inv:e1": {"type": "edition", "uri": "inv:e1", "labels": {"fromclaims": "One Piece, Vol. 1"}, "claims": {
    "wdt:P212": ["978-1-56931-901-7"], "wdt:P957": ["1-56931-901-4"], "wdt:P407": ["wd:Q1860"], "wdt:P1476": ["One Piece, Vol. 1"],
    "wdt:P577": ["2003-06-01"], "wdt:P629": ["wd:W1"], "wdt:P123": ["wd:P1"], "invp:P2": ["abc"], "wdt:P648": ["OL1M"]}}},
    "redirects": {"isbn:" + ISBN: "inv:e1"}}).encode()
RELATED = json.dumps({"entities": {"wd:W1": {"labels": {"en": "One Piece"}, "claims": {"wdt:P50": ["wd:A1"], "wdt:P179": ["wd:S1"], "wdt:P1545": ["1"]}},
                                   "wd:P1": {"labels": {"en": "VIZ Media"}, "claims": {}}}}).encode()
AUTH = json.dumps({"entities": {"wd:A1": {"labels": {"en": "Eiichiro Oda"}}, "wd:S1": {"labels": {"en": "One Piece (series)"}}}}).encode()


class InventaireParsing(unittest.TestCase):
    def test_url_uses_documented_read_only_route_without_refresh(self):
        u = V.url(["isbn:" + ISBN, "wd:Q1"], ["labels", "claims"])
        self.assertTrue(u.startswith("https://inventaire.io/api/entities/by-uris?uris=isbn:" + ISBN + "|wd:Q1"))
        self.assertIn("attributes=labels|claims", u)
        for forbidden in ("refresh", "autocreate", "create"):
            self.assertNotIn(forbidden, u)

    def test_edition_is_normalized_with_provenance_and_unknowns_kept_unknown(self):
        n = V.parse(ISBN, EDITION)
        self.assertEqual(("OK", "edition", "inv"), (n.status, n.kind, n.entity_origin))
        self.assertEqual([ISBN], n.returned_isbns)
        self.assertEqual(("One Piece, Vol. 1", "en", "2003-06-01"), (n.title, n.language, n.published))
        self.assertIsNone(n.publisher); self.assertIsNone(n.series); self.assertIsNone(n.volume); self.assertEqual([], n.creators)   # not guessed
        self.assertTrue(n.cover_present); self.assertEqual("inventaire-hosted", n.cover_origin); self.assertEqual("OL1M", n.links["openlibrary"])

    def test_related_entities_fill_publisher_series_creator(self):
        merged = json.loads(RELATED); merged["entities"].update(json.loads(AUTH)["entities"])
        n = V.parse(ISBN, EDITION, json.dumps(merged).encode())
        self.assertEqual(("VIZ Media", "One Piece (series)", "1", ["Eiichiro Oda"]), (n.publisher, n.series, n.volume, n.creators))

    def test_fetch_makes_at_most_three_requests_and_never_a_write(self):
        c = client([(200, {}, EDITION), (200, {}, RELATED), (200, {}, AUTH)])
        n, _ = V.fetch(c, ISBN)
        self.assertEqual(3, n.requests); self.assertEqual("One Piece (series)", n.series)
        self.assertTrue(all(h["Accept"] == "application/json" for _, h in c.transport.calls))

    def test_no_edition_is_no_result_but_transport_error_is_not(self):
        n = V.parse(ISBN, json.dumps({"entities": {}, "redirects": {}}).encode())
        self.assertEqual("NO_RESULT", n.status)
        n2, _ = V.fetch(client([OSError("net")] * 4, retries=1), ISBN)
        self.assertEqual("REQUEST_FAILED", n2.status)

    def test_work_entity_is_not_an_edition_and_multiple_editions_are_counted(self):
        work = json.dumps({"entities": {"wd:W1": {"type": "work", "claims": {}}}}).encode()
        self.assertEqual("NO_RESULT", V.parse(ISBN, work).status)
        two = json.loads(EDITION); two["entities"]["inv:e2"] = dict(two["entities"]["inv:e1"], uri="inv:e2")
        self.assertEqual(2, V.parse(ISBN, json.dumps(two).encode()).candidates)

    def test_malformed_responses(self):
        for body in (b"not json", b"[]", b'{"entities": 3}', b""):
            self.assertEqual("REQUEST_FAILED", V.parse(ISBN, body).status)


def sparql(rows):
    return json.dumps({"head": {"vars": []}, "results": {"bindings": rows}}).encode()


def b(**kw):
    return {k: {"type": "literal", "value": v} for k, v in kw.items()}


class WikidataParsing(unittest.TestCase):
    def test_query_is_bounded_to_one_isbn_read_only(self):
        q = W.build_query(ISBN)
        self.assertIn('"978-1-56931-901-7"', q); self.assertIn('"1-56931-901-4"', q)
        self.assertTrue(q.rstrip().endswith("LIMIT 60"))
        self.assertTrue(W.url(ISBN).startswith("https://query.wikidata.org/sparql?query="))
        parsed = urllib.parse.parse_qs(urllib.parse.urlparse(W.url(ISBN)).query)
        self.assertEqual(["json"], parsed["format"])
        self.assertNotIn("INSERT", q.upper()); self.assertNotIn("DELETE", q.upper())

    def test_rows_merge_into_one_edition(self):
        rows = [b(item="http://www.wikidata.org/entity/Q1", isbn="978-1-56931-901-7", title="One Piece, Vol. 1", langCode="en", pubLabel="VIZ Media",
                  date="2003-06-01T00:00:00Z", work="http://www.wikidata.org/entity/Q2", authorLabel="Eiichiro Oda", ord="1", seriesLabel="One Piece"),
                b(item="http://www.wikidata.org/entity/Q1", isbn="978-1-56931-901-7", title="One Piece, Vol. 1", authorLabel="Eiichiro Oda", image="http://commons/x.jpg")]
        n = W.parse(ISBN, sparql(rows))
        self.assertEqual(("OK", "wd:Q1", "edition", "wd"), (n.status, n.record_id, n.kind, n.entity_origin))
        self.assertEqual(([ISBN], ["Eiichiro Oda"], "VIZ Media", "en", "1", "2003-06-01"), (n.returned_isbns, n.creators, n.publisher, n.language, n.volume, n.published))
        self.assertTrue(n.cover_present); self.assertEqual("wikimedia-commons", n.cover_origin)

    def test_no_rows_is_no_result_and_malformed_is_failure(self):
        self.assertEqual("NO_RESULT", W.parse(ISBN, sparql([])).status)
        self.assertEqual("REQUEST_FAILED", W.parse(ISBN, b"<html>").status)
        self.assertEqual("REQUEST_FAILED", W.parse(ISBN, b'{"results": 4}').status)

    def test_two_items_for_one_isbn_are_ambiguous_candidates(self):
        rows = [b(item="http://www.wikidata.org/entity/Q1", isbn=ISBN), b(item="http://www.wikidata.org/entity/Q9", isbn=ISBN)]
        self.assertEqual(2, W.parse(ISBN, sparql(rows)).candidates)

    def test_unknown_stays_unknown(self):
        n = W.parse(ISBN, sparql([b(item="http://www.wikidata.org/entity/Q1", isbn=ISBN)]))
        self.assertIsNone(n.title); self.assertIsNone(n.language); self.assertIsNone(n.volume); self.assertFalse(n.cover_present)


def fx(**kw):
    base = dict(id="x", isbn13=ISBN, expected_title="One Piece, Vol. 1: Romance Dawn", expected_creator="Eiichiro Oda", expected_language="en",
                expected_publisher="VIZ Media", expected_series="One Piece", expected_volume="1", edition_type="single", category="manga_en", verification="multi-source")
    base.update(kw)
    return base


def rec(**kw):
    base = dict(provider="p", queried_isbn=ISBN, status="OK", error=None, record_id="r", returned_isbns=[ISBN], title="One Piece, Vol. 1: Romance Dawn", creators=["Eiichiro Oda"],
                publisher="VIZ Media", language="en", series="One Piece", volume="1", published="2003", cover_present=True, candidates=1, seconds=0.5, requests=1)
    base.update(kw)
    return base


class Classification(unittest.TestCase):
    def cls(self, f=None, **r):
        return classify.classify(f or fx(), rec(**r))[0]

    def test_classes(self):
        self.assertEqual("EXACT_EDITION", self.cls())
        self.assertEqual("NO_RESULT", self.cls(status="NO_RESULT"))
        self.assertEqual("REQUEST_FAILED", self.cls(status="REQUEST_FAILED", error="HTTP 503"))
        self.assertEqual("WRONG_EDITION", self.cls(returned_isbns=["9780743273565"]))
        self.assertEqual("WRONG_EDITION", self.cls(language="ja"))
        self.assertEqual("WRONG_EDITION", self.cls(title="Completely Different"))
        self.assertEqual("CONFLICTING_VOLUME", self.cls(volume="10"))
        self.assertEqual("AMBIGUOUS", self.cls(candidates=2))
        self.assertEqual("PARTIAL_EDITION", self.cls(volume=None))
        self.assertEqual("PARTIAL_EDITION", self.cls(title=None))

    def test_series_name_without_volume_is_a_fragment_not_exact(self):
        b = fx(expected_title="Batman: Year One")
        self.assertEqual("PARTIAL_EDITION", self.cls(b, title="Batman", volume=None))
        self.assertEqual("EXACT_EDITION", self.cls(fx(expected_title="Death Note, Vol. 1: Boredom"), title="Death Note, Vol. 1"))
        self.assertEqual("WRONG_EDITION", self.cls(fx(), title="Totally Unrelated Latin Words"))

    def test_volume_one_vs_ten_and_omnibus(self):
        self.assertEqual("CONFLICTING_VOLUME", self.cls(fx(expected_volume="10"), volume="1"))
        omni = fx(expected_title="Berserk Deluxe Edition Volume 1", expected_volume="1-3", edition_type="omnibus")
        self.assertEqual("PARTIAL_EDITION", self.cls(omni, title="Berserk Deluxe Edition Volume 1", volume="1"))
        self.assertEqual("WRONG_EDITION", self.cls(omni, title="Berserk, Vol. 1"))

    def test_japanese_title_containment_without_translation(self):
        j = fx(expected_title="進撃の巨人 1", expected_language="ja")
        self.assertEqual("EXACT_EDITION", self.cls(j, title="進撃の巨人 1", language="ja"))
        # the ISBN identifies the edition; a title in another script is not comparable (no translation is assumed), so PARTIAL, never EXACT
        self.assertEqual("PARTIAL_EDITION", self.cls(j, title="Attack on Titan 1", language="ja"))


class BridgeAndReport(unittest.TestCase):
    def test_tsv_round_trip_and_wrong_volume_probe(self):
        f = fx(expected_title="A\tB\nC")
        rows = bridge.rows_for(f, [rec()])
        wrong = bridge.rows_for(f, [rec()], wrong_volume=True)
        self.assertEqual("normal", rows[0][1]); self.assertEqual("wrongvol", wrong[0][1]); self.assertEqual("2", wrong[0][7])
        self.assertEqual([], bridge.rows_for(fx(expected_volume="1-3"), [rec()], wrong_volume=True))   # ranges have no "next" volume
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "in.tsv"); bridge.write_tsv(p, rows)
            line = open(p, encoding="utf-8").read().rstrip("\n")
            self.assertEqual(len(rows[0]), len(line.split("\t")))
            self.assertNotIn("\n", line)
            self.assertEqual("A\tB\nC", urllib.parse.unquote(line.split("\t")[4]))
            out = os.path.join(d, "out.tsv")
            open(out, "w", encoding="utf-8").write("\t".join(urllib.parse.quote(x) for x in ["p:x", "normal", "VERY_HIGH", "false", "true", "IDENTIFIER,TITLE", ""]) + "\n")
            dec = bridge.read_decisions(out)[("p:x", "normal")]
            self.assertEqual("AUTO_ACCEPT", bridge.decision_for(dec)); self.assertEqual(["IDENTIFIER", "TITLE"], dec["agreements"])
        self.assertEqual("REVIEW", bridge.decision_for(dict(apply=False, rejected=False, tier="MEDIUM")))
        self.assertEqual("REJECT", bridge.decision_for(dict(apply=False, rejected=True, tier="LOW")))
        self.assertEqual("INSUFFICIENT_EVIDENCE", bridge.decision_for(None))

    def test_scorecard_denominators_csv_and_json(self):
        fixtures = [fx(id="a"), fx(id="b", category="books_en"), fx(id="c")]
        records = {("p", "a"): rec(), ("p", "b"): rec(status="REQUEST_FAILED", error="HTTP 503"), ("p", "c"): rec(status="NO_RESULT", returned_isbns=[])}
        decisions = {("p", "a", "normal"): dict(tier="VERY_HIGH", rejected=False, apply=True, agreements=[], contradictions=[]),
                     ("p", "a", "wrongvol"): dict(tier="LOW", rejected=True, apply=False, agreements=[], contradictions=["VOLUME"])}
        rows = report.evaluate(fixtures, records, decisions)
        sc = report.scorecard(rows, "p", [("a", "manga_en"), ("b", "books_en"), ("c", "manga_en")])
        self.assertEqual((3, 2, 1, 1, 1, 1, 1), (sc["ALL"]["attempted"], sc["ALL"]["http_ok"], sc["ALL"]["ok"], sc["ALL"]["exact"], sc["ALL"]["accept"], sc["ALL"]["failed"], sc["ALL"]["none"]))
        self.assertEqual((0, 1), (sc["ALL"]["wrongvol_accept"], sc["ALL"]["wrongvol_n"]))
        self.assertIn("1/3 (33%)", report.markdown("p", sc))
        self.assertEqual("0/0 (n/a)", report.frac(0, 0))
        with tempfile.TemporaryDirectory() as d:
            report.write_outputs(d, rows, {"p": sc}, {"x": 1})
            data = list(csv.reader(open(os.path.join(d, "cases.csv"), encoding="utf-8")))
            self.assertEqual(4, len(data)); self.assertIn("class", data[0])
            self.assertEqual(3, len(json.load(open(os.path.join(d, "summary.json"), encoding="utf-8"))["cases"]))

    def test_latency_percentile_only_when_the_sample_supports_it(self):
        rows = [dict(provider="p", id=str(i), category="books_en", verification="m", cls="EXACT_EDITION", reasons="", shelfos="REVIEW", tier=None, wrongvol=None, requests=1,
                     seconds=float(i + 1), status="OK", error=None, record=rec()) for i in range(5)]
        sc = report.scorecard(rows, "p", [(str(i), "books_en") for i in range(5)])
        self.assertEqual(3.0, sc["ALL"]["lat_median"]); self.assertIsNone(sc["ALL"]["lat_p95"])


if __name__ == "__main__":
    unittest.main()
