"""Stage A for the BNE experiment: offline only. Every XML below is a small synthetic sample written for these tests
(shaped like SRU 1.2 / MARCXML, not copied from the service)."""
import json
import os
import sys
import unittest
import urllib.parse

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from lab import classify                       # noqa: E402
from lab.http import Client, Blocked           # noqa: E402
from providers import bne                      # noqa: E402
from experiments import bne_spanish            # noqa: E402

ISBN = "9788468471525"        # One Piece 1 (Planeta), a fixture ISBN-13
ISBN10 = "8468471526"


def df(tag, **subs):
    body = "".join('<subfield code="%s">%s</subfield>' % (c, v) for c, v in subs.items())
    return '<datafield tag="%s" ind1=" " ind2=" ">%s</datafield>' % (tag, body)


def marc(fields, ns=True, f001="BNE001", f008="900101s2004    sp |||||||||||||||||spa  "):
    x = ' xmlns="http://www.loc.gov/MARC21/slim"' if ns else ""
    return '<record%s><controlfield tag="001">%s</controlfield><controlfield tag="008">%s</controlfield>%s</record>' % (x, f001, f008, "".join(fields))


def sru(records, total=None, diag=None):
    recs = "".join('<srw:record xmlns:srw="http://www.loc.gov/zing/srw/"><srw:recordData>%s</srw:recordData></srw:record>' % r for r in records)
    d = '<srw:diagnostics><diag:diagnostic xmlns:diag="http://www.loc.gov/zing/srw/diagnostic/"><diag:details>%s</diag:details></diag:diagnostic></srw:diagnostics>' % diag if diag else ""
    return ('<?xml version="1.0"?><srw:searchRetrieveResponse xmlns:srw="http://www.loc.gov/zing/srw/"><srw:version>1.2</srw:version>'
            '<srw:numberOfRecords>%d</srw:numberOfRecords><srw:records>%s</srw:records>%s</srw:searchRetrieveResponse>' % (len(records) if total is None else total, recs, d)).encode("utf-8")


ONEPIECE = [df("020", a="9788468471525 (rústica)"), df("100", a="Oda, Eiichirō,", e="autor"), df("245", a="One Piece /", c="Eiichiro Oda ; [traducción, Verónica Calafell]"),
            df("250", a="1ª ed."), df("264", a="Barcelona :", b="Planeta Cómic,", c="2017"), df("490", a="One Piece ;", v="n.º 1"), df("041", a="spa", h="jpn")]


class Urls(unittest.TestCase):
    def test_explain_and_search_urls_follow_the_documented_contract(self):
        self.assertEqual("https://catalogo.bne.es/view/sru/34BNE_INST?version=1.2&operation=explain", bne.explain_url())
        u = bne.search_url(ISBN)
        q = urllib.parse.parse_qs(urllib.parse.urlparse(u).query)
        self.assertEqual(["searchRetrieve"], q["operation"]); self.assertEqual(["1.2"], q["version"]); self.assertEqual(["marcxml"], q["recordSchema"])
        self.assertEqual(['alma.isbn="9788468471525" or alma.isbn="%s"' % ISBN10], q["query"])
        self.assertEqual(["5"], q["maximumRecords"]); self.assertEqual(["1"], q["startRecord"])
        self.assertTrue(u.startswith("https://catalogo.bne.es/view/sru/34BNE_INST?"))

    def test_result_cap_is_bounded_and_isbn_validated(self):
        self.assertIn("maximumRecords=10", bne.search_url(ISBN, maximum=500)); self.assertIn("maximumRecords=1&", bne.search_url(ISBN, maximum=-4))
        for bad in ("9788468471526", "not-an-isbn", '97884684715"25', ""):
            with self.assertRaises(ValueError):
                bne.search_url(bad)

    def test_cql_literals_are_escaped(self):
        self.assertEqual('"a\\"b\\\\c"', bne.cql_quote('a"b\\c'))
        self.assertNotIn(" or alma.isbn=", bne.isbn_query("9791234567896"))      # 979 prefix: no ISBN-10 form


class Explain(unittest.TestCase):
    def test_explain_is_read_for_what_it_declares(self):
        body = (b'<explainResponse xmlns="http://www.loc.gov/zing/srw/"><version>1.2</version><record><recordData><explain xmlns="http://explain.z3950.org/dtd/2.0/">'
                b'<indexInfo><index><title>ISBN</title><map><name set="alma">isbn</name></map></index><index><title>Title</title><map><name set="alma">title</name></map></index></indexInfo>'
                b'<schemaInfo><schema identifier="info:srw/schema/1/marcxml-v1.1" name="marcxml"/></schemaInfo></explain></recordData></record></explainResponse>')
        e = bne.parse_explain(body)
        self.assertEqual("1.2", e["version"]); self.assertIn("ISBN", e["indexes"]); self.assertEqual(["marcxml"], e["schemas"])

    def test_malformed_or_hostile_xml_is_refused(self):
        for body in (b"<a>", b"not xml", b'<!DOCTYPE x [<!ENTITY y "z">]><x>&y;</x>', b""):
            with self.assertRaises(bne.ParseError):
                bne.parse_explain(body)


class Marc(unittest.TestCase):
    def one(self, fields, **kw):
        return bne.parse(ISBN, sru([marc(fields, **kw)]))

    def test_a_complete_record_is_read_field_by_field(self):
        n = self.one(ONEPIECE)
        self.assertEqual("OK", n.status); self.assertEqual(["9788468471525"], n.returned_isbns)
        self.assertEqual("One Piece", n.title); self.assertEqual(["Oda, Eiichirō"], n.creators)
        self.assertEqual(("Planeta Cómic", "2017", "es", "One Piece", "1"), (n.publisher, n.published, n.language, n.series, n.volume))
        self.assertEqual("1ª ed", n.edition_type); self.assertEqual("BNE001", n.record_id)
        self.assertFalse(n.cover_present)

    def test_works_without_a_namespace_on_the_marc_record(self):
        self.assertEqual("One Piece", self.one(ONEPIECE, ns=False).title)

    def test_diacritics_are_preserved_and_nothing_is_translated(self):
        n = self.one([df("020", a=ISBN), df("245", a="Ataque a los Titanes ¡Épico!"), df("100", a="Isayama, Hajime")])
        self.assertEqual("Ataque a los Titanes ¡Épico!", n.title)

    def test_unknown_stays_unknown(self):
        n = self.one([df("020", a=ISBN), df("245", a="Título")], f008="x" * 38)
        self.assertEqual((None, None, None, None, None), (n.publisher, n.published, n.series, n.volume, n.edition_type))
        self.assertIsNone(n.language)                       # 008 without a language code
        self.assertEqual([], n.creators)

    def test_volume_is_never_invented_or_guessed_from_free_text(self):
        for text, want in (("n.º 1", "1"), ("v. 12", "12"), ("1", "1"), ("1-3", "1-3"), ("tomo 2", "2"), ("Primera parte", None), ("1 de 72", None), ("", None)):
            self.assertEqual(want, bne.volume_of(text), text)
        n = self.one([df("020", a=ISBN), df("245", a="One Piece")])
        self.assertIsNone(n.volume)                         # a first record, or an ISBN, is not volume 1

    def test_series_title_is_not_the_edition_title(self):
        n = self.one([df("020", a=ISBN), df("245", a="Primer arco"), df("490", a="One Piece ;", v="1")])
        self.assertEqual(("Primer arco", "One Piece"), (n.title, n.series))

    def test_isbn_qualifiers_hyphens_isbn10_and_cancelled_isbns(self):
        rec = marc([df("020", a="978-84-684-7152-5 (rústica)"), df("020", a="8468471526"), df("020", z="9788468471532")])
        n = bne.parse(ISBN, sru([rec]))
        self.assertEqual(["9788468471525"], n.returned_isbns)         # the ISBN-10 maps to the same ISBN-13
        self.assertEqual(["9788468471532"], n.links["cancelled_isbns"])

    def test_a_record_for_a_different_isbn_is_flagged_as_not_containing_the_query(self):
        n = bne.parse(ISBN, sru([marc([df("020", a="9788467960921"), df("245", a="Otro")])]))
        self.assertFalse(n.links["queried_isbn_in_record"])

    def test_multiple_records_are_preserved_and_counted(self):
        a = marc(ONEPIECE, f001="A")
        b = marc([df("020", a=ISBN), df("245", a="One Piece"), df("250", a="Edición integral")], f001="B")
        n = bne.parse(ISBN, sru([a, b], total=2))
        self.assertEqual(2, n.candidates); self.assertEqual(1, len(n.links["other_records"])); self.assertEqual("Edición integral", n.links["other_records"][0]["edition"])

    def test_empty_result_is_no_result_but_a_diagnostic_or_garbage_is_not(self):
        self.assertEqual("NO_RESULT", bne.parse(ISBN, sru([])).status)
        d = bne.parse(ISBN, sru([], diag="Unsupported index"))
        self.assertEqual("REQUEST_FAILED", d.status); self.assertIn("Unsupported index", d.error)
        for body in (b"<html>blocked</html>", b"<x/", b"", sru([marc(ONEPIECE)], total=3)[:-30]):
            self.assertEqual("REQUEST_FAILED", bne.parse(ISBN, body).status)
        liar = bne.parse(ISBN, sru([], total=4))
        self.assertEqual("REQUEST_FAILED", liar.status)             # claims 4 records, returns none

    def test_conflicting_isbns_and_wrong_volume_and_omnibus_classification(self):
        fx = dict(id="x", isbn13=ISBN, expected_title="One Piece nº 1", expected_creator="Eiichiro Oda", expected_language="es", expected_publisher="Planeta Cómic",
                  expected_series="One Piece", expected_volume="1", edition_type="single", category="manga_es", verification="multi-source")
        good = self.one(ONEPIECE).to_dict()
        self.assertEqual("EXACT_EDITION", classify.classify(fx, good)[0])
        wrong_vol = self.one([df("020", a=ISBN), df("245", a="One Piece"), df("490", a="One Piece", v="10"), df("041", a="spa")]).to_dict()
        self.assertEqual("CONFLICTING_VOLUME", classify.classify(fx, wrong_vol)[0])
        other_isbn = bne.parse(ISBN, sru([marc([df("020", a="9788467960921"), df("245", a="One Piece")])])).to_dict()
        self.assertEqual("WRONG_EDITION", classify.classify(fx, other_isbn)[0])
        ja = self.one([df("020", a=ISBN), df("245", a="One Piece"), df("041", a="jpn")]).to_dict()
        self.assertEqual("WRONG_EDITION", classify.classify(fx, ja)[0])
        omni = dict(fx, id="o", expected_title="Maximum Berserk 1", expected_volume="1", edition_type="omnibus")
        om = self.one([df("020", a=ISBN), df("245", a="Maximum Berserk"), df("490", a="Berserk", v="1"), df("041", a="spa")]).to_dict()
        bare = self.one([df("020", a=ISBN), df("245", a="Berserk"), df("490", a="Berserk", v="1"), df("041", a="spa")]).to_dict()
        self.assertNotEqual("EXACT_EDITION", bne_spanish.classify_p1(omni, bare)[0], "a bare '1' does not prove a single volume or an omnibus")
        self.assertEqual("EXACT_EDITION", bne_spanish.classify_p1(omni, om)[0])      # the record itself says Maximum
        noisbn = self.one([df("245", a="Maximum Berserk"), df("041", a="spa")]).to_dict()
        self.assertEqual("PARTIAL_EDITION", bne_spanish.classify_p1(omni, noisbn)[0])
        two = bne.parse(ISBN, sru([marc(ONEPIECE, f001="A"), marc(ONEPIECE, f001="B")])).to_dict()
        self.assertEqual("AMBIGUOUS", classify.classify(fx, two)[0])


class Transport(unittest.TestCase):
    def client(self, responses, **kw):
        calls = []

        def t(url, headers, timeout, max_bytes):
            calls.append((url, headers))
            r = responses.pop(0)
            if isinstance(r, Exception):
                raise r
            return r
        c = Client(bne.HOST, transport=t, sleep=lambda s: None, clock=lambda: 0.0, accept="application/xml", **kw)
        c.calls = calls
        return c

    def test_host_bound_identified_and_two_second_pacing_default(self):
        c = self.client([(200, {}, b"<x/>")])
        c.get(bne.explain_url())
        self.assertIn("ShelfOS", c.calls[0][1]["User-Agent"])
        with self.assertRaises(ValueError):
            c.get("https://www.bne.es/x")
        with self.assertRaises(ValueError):
            c.get("http://catalogo.bne.es/view/sru/34BNE_INST")

    def test_403_and_429_stop_the_provider_and_are_never_a_missing_record(self):
        for code in (403, 429):
            c = self.client([(code, {"Retry-After": "120"}, b"<html>challenge</html>")])
            with self.assertRaises(Blocked) as e:
                c.get(bne.search_url(ISBN))
            self.assertIn(str(code), str(e.exception))
            with self.assertRaises(Blocked):
                c.get(bne.search_url(ISBN))
            self.assertEqual(1, len(c.calls))

    def test_oversized_and_failed_responses_are_errors(self):
        c = self.client([(200, {}, b"x" * 50)], max_bytes=10)
        self.assertIn("larger", c.get(bne.explain_url()).error)
        c2 = self.client([(500, {}, b"")] * 4, retries=1)
        r = c2.get(bne.explain_url())
        self.assertFalse(r.ok); self.assertEqual(2, len(c2.calls))


class Corpus(unittest.TestCase):
    def test_the_six_spanish_fixtures_are_exactly_the_p0_ones(self):
        corpus = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fixtures", "isbn_corpus.json"), encoding="utf-8"))
        es = {f["id"]: f["isbn13"] for f in corpus["fixtures"] if f["category"] == "manga_es"}
        self.assertEqual({"es-onepiece1": "9788468471525", "es-naruto1": "9788415821816", "es-aot1": "9788467960921",
                          "es-maxberserk1": "9788411015035", "es-chainsawman1": "9788467941159", "es-akira2": "9788467939903"}, es)


class ExperimentReport(unittest.TestCase):
    """The P1 report never presents an unavailable matcher result as measured, and never touches P0 files."""

    def setUp(self):
        import contextlib
        import io
        import shutil
        import tempfile
        self.d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.d, True)
        self.saved = bne_spanish.OUT
        bne_spanish.OUT = self.d
        self.addCleanup(setattr, bne_spanish, "OUT", self.saved)
        self.args = type("A", (), dict(jvm=False))()
        self.cm, self.io = contextlib.redirect_stdout, io.StringIO

    def rec(self, fid, **kw):
        base = dict(provider="bne", queried_isbn=bne_spanish.SPANISH[fid], status="NO_RESULT", error=None, record_id=None, returned_isbns=[], kind=None, title=None,
                    creators=[], publisher=None, language=None, series=None, volume=None, published=None, edition_type=None, cover_present=False, cover_origin=None,
                    links={}, entity_origin=None, candidates=0, seconds=0.9, requests=1)
        base.update(kw)
        return base

    def write(self, records):
        json.dump(records, open(os.path.join(self.d, "records.json"), "w", encoding="utf-8"))

    def test_no_recorded_results_is_a_failure_and_writes_nothing(self):
        with self.assertRaises(SystemExit) as cm:
            bne_spanish.make_report(self.args)
        self.assertIn("FAILED", str(cm.exception.code)); self.assertEqual([], os.listdir(self.d))

    def test_six_no_results_are_reported_as_such_without_a_matcher_claim(self):
        self.write({"bne|" + f: self.rec(f) for f in bne_spanish.SPANISH})
        with self.cm(self.io()):
            bne_spanish.make_report(self.args)
        s = json.load(open(os.path.join(self.d, "summary.json"), encoding="utf-8"))
        self.assertEqual("no-ok-records", s["meta"]["bridge_verification"]["mode"]); self.assertEqual(6, s["meta"]["evaluated"])
        a = s["scorecards"]["bne"]["ALL"]
        self.assertEqual((6, 6, 0, 0, 6, 0), (a["attempted"], a["http_ok"], a["ok"], a["exact"], a["none"], a["accept"]))
        raw = open(os.path.join(self.d, "cases.csv"), "rb").read()
        self.assertEqual(0, raw.count(b"\r")); self.assertEqual(7, raw.count(b"\n"))
        self.assertTrue(all(c["shelfos"] == "INSUFFICIENT_EVIDENCE" for c in s["cases"]))

    def test_a_failed_request_is_never_reported_as_no_result(self):
        self.write({"bne|es-akira2": self.rec("es-akira2", status="REQUEST_FAILED", error="ACCESS_BLOCKED: HTTP 403")})
        with self.cm(self.io()):
            bne_spanish.make_report(self.args)
        cases = json.load(open(os.path.join(self.d, "summary.json"), encoding="utf-8"))["cases"]
        akira = [c for c in cases if c["id"] == "es-akira2"][0]
        self.assertEqual("REQUEST_FAILED", akira["cls"]); self.assertIn("ACCESS_BLOCKED", akira["reasons"])
        self.assertEqual("NOT_EVALUATED", [c for c in cases if c["id"] == "es-naruto1"][0]["cls"])

    def test_an_ok_record_without_a_verified_snapshot_fails_closed(self):
        self.write({"bne|es-onepiece1": self.rec("es-onepiece1", status="OK", record_id="1", returned_isbns=["9788468471525"], title="One Piece", language="es")})
        with self.assertRaises(SystemExit) as cm:
            bne_spanish.make_report(self.args)
        self.assertIn("verification failed", str(cm.exception.code)); self.assertIn("No report outputs were updated", str(cm.exception.code))
        self.assertEqual(["records.json"], os.listdir(self.d))

    def test_only_the_six_spanish_fixtures_are_ever_queried(self):
        self.assertEqual(6, len(bne_spanish.SPANISH)); self.assertEqual(2, len(bne_spanish.PILOT))
        self.assertLessEqual(bne_spanish.MAX_REQUESTS, 12); self.assertGreaterEqual(bne_spanish.INTERVAL, 2.0)


if __name__ == "__main__":
    unittest.main()
