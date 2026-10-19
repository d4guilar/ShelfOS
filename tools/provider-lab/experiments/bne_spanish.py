#!/usr/bin/env python3
"""Phase 4.5C.P1: BNE (SRU 1.2) on the six Spanish Manga fixtures. Research only; isolated from the P0 benchmark.

  python experiments/bne_spanish.py explain        1 request: the service's own Explain response
  python experiments/bne_spanish.py pilot          2 requests: One Piece 1 (ordinary) and Maximum Berserk 1 (omnibus)
  python experiments/bne_spanish.py full           the remaining 4 ISBNs (pilot results are reused, never repeated)
  python experiments/bne_spanish.py report [--jvm] classify, verify the production matcher bridge, write results/bne_spanish/

P0 files in results/ are never read for writing here. All outputs go to results/bne_spanish/."""
import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LAB = os.path.abspath(os.path.join(HERE, ".."))
sys.path.insert(0, LAB)
from lab import bridge, classify, report                      # noqa: E402
from lab.http import Client, Blocked                          # noqa: E402
from lab.model import Normalized                              # noqa: E402
from providers import bne                                     # noqa: E402

CORPUS = os.path.join(LAB, "fixtures", "isbn_corpus.json")
OUT = os.path.join(LAB, "results", "bne_spanish")
RAW = os.path.join(OUT, "raw")
SPANISH = {"es-onepiece1": "9788468471525", "es-naruto1": "9788415821816", "es-aot1": "9788467960921",
           "es-maxberserk1": "9788411015035", "es-chainsawman1": "9788467941159", "es-akira2": "9788467939903"}
PILOT = ["es-onepiece1", "es-maxberserk1"]
INTERVAL = 2.0
MAX_REQUESTS = 12
OMNIBUS_MARKERS = ("maximum", "omnibus", "ómnibus", "integral", "deluxe", "coleccionista", "3 en 1", "2 en 1", "edición especial", "kanzenban", "colossal", "compendio")


def fixtures():
    fx = {f["id"]: f for f in json.load(open(CORPUS, encoding="utf-8"))["fixtures"] if f["category"] == "manga_es"}
    if {k: v["isbn13"] for k, v in fx.items()} != SPANISH:
        raise SystemExit("the Spanish fixtures differ from the six P0 fixtures; refusing to run")
    return fx


def classify_p1(fx, rec):
    """P0's classifier plus two P1-only guards: (1) a collection fixture is exact only when the record itself carries collection
    evidence (a bare volume number proves neither a single volume nor an omnibus); (2) a record with no ISBN of its own is partial."""
    cls, why = classify.classify(fx, rec)
    if cls != "EXACT_EDITION":
        return cls, why
    if not rec["returned_isbns"]:
        return "PARTIAL_EDITION", ["the record lists no ISBN"]
    if fx["edition_type"] in ("omnibus", "collected"):
        text = " ".join(str(x or "") for x in (rec.get("title"), rec.get("edition_type"), rec.get("series"), (rec.get("links") or {}).get("title_subtitle"))).lower()
        if not any(m in text for m in OMNIBUS_MARKERS):
            return "PARTIAL_EDITION", ["collection fixture but the record shows no collection evidence (volume %s alone is not proof)" % rec.get("volume")]
    return cls, why


def new_client():
    return Client(bne.HOST, min_interval=INTERVAL, timeout=20.0, max_bytes=400_000, max_requests=MAX_REQUESTS, retries=0, accept="application/xml")


def load_records():
    p = os.path.join(OUT, "records.json")
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else {}


def fetch(client, fx):
    r = client.get(bne.search_url(fx["isbn13"]))
    os.makedirs(RAW, exist_ok=True)
    open(os.path.join(RAW, "bne_%s.xml" % fx["id"]), "wb").write(r.body or b"")
    if not r.ok:
        n = Normalized("bne", fx["isbn13"], status="REQUEST_FAILED", error=r.error)
    else:
        n = bne.parse(fx["isbn13"], r.body)
    n.requests, n.seconds = 1, r.elapsed
    return n


def explain(_):
    c = new_client()
    r = c.get(bne.explain_url())
    os.makedirs(RAW, exist_ok=True)
    open(os.path.join(RAW, "explain.xml"), "wb").write(r.body or b"")
    print("HTTP", r.status, r.error, len(r.body or b""), "bytes", "%.2fs" % r.elapsed)
    if r.ok:
        e = bne.parse_explain(r.body)
        json.dump(e, open(os.path.join(OUT, "explain.json"), "w", encoding="utf-8"), indent=1)
        print("version", e["version"], "| indexes:", len(e["indexes"]), "| alma.isbn declared:", any("isbn" in i.lower() for i in e["indexes"]), "| schemas:", e["schemas"])
    with open(os.path.join(OUT, "requests.csv"), "a", encoding="utf-8", newline="\n") as f:
        f.write("explain,%s,%.2f,%s\n" % (r.status, r.elapsed, r.error or ""))


def control(_):
    """Two bounded controls for the ISBN query mechanism, since two empty answers alone cannot tell 'not held' from 'wrong query form':
    (1) a title search (3 records max) to see how BNE stores ISBNs; (2) an ISBN search for an ISBN-13 that (1) returned."""
    import urllib.parse
    c = new_client()
    u1 = bne.BASE + "?" + urllib.parse.urlencode({"version": "1.2", "operation": "searchRetrieve", "query": 'alma.title="One Piece"', "recordSchema": "marcxml",
                                                    "maximumRecords": 3, "startRecord": 1}, quote_via=urllib.parse.quote)
    r1 = c.get(u1)
    open(os.path.join(RAW, "control_title.xml"), "wb").write(r1.body or b"")
    res = bne.parse_search(r1.body)
    print("title search: HTTP", r1.status, "| total reported", res["total"], "| returned", len(res["records"]))
    found = None
    for rec in res["records"]:
        good, _ = bne.isbns(rec)
        t = bne.normalize_record("0", rec)
        print("   record", t.record_id, "|", t.title, "|", good, "|", t.publisher, t.published, "| lang", t.language, "| series", t.series, t.volume)
        valid = [g for g in good if g.startswith("978") and len(g) == 13]
        if valid and not found:
            found = valid[0]
    if not found:
        print("no ISBN-13 in the control records; ISBN-form control not possible"); return
    r2 = c.get(bne.search_url(found))
    open(os.path.join(RAW, "control_isbn.xml"), "wb").write(r2.body or b"")
    res2 = bne.parse_search(r2.body)
    print("isbn control", found, ": HTTP", r2.status, "| total", res2["total"], "| returned", len(res2["records"]))
    with open(os.path.join(OUT, "requests.csv"), "a", encoding="utf-8", newline="\n") as f:
        for url, st, secs, err in c.log:
            f.write("control,%s,%.2f,%s\n" % (st, secs, err or ""))


def diagnose(_):
    """Holdings diagnostic (3 requests, 3 records each): does BNE catalogue these works at all under other ISBNs? This separates
    'edition not held / not catalogued' from 'work unknown'. It is context for the no-result interpretation, not a benchmark."""
    import urllib.parse
    c = new_client()
    out = {}
    for title in ("Chainsaw Man", "Ataque a los Titanes", "Naruto"):
        u = bne.BASE + "?" + urllib.parse.urlencode({"version": "1.2", "operation": "searchRetrieve", "query": "alma.title=" + bne.cql_quote(title), "recordSchema": "marcxml",
                                                       "maximumRecords": 3, "startRecord": 1}, quote_via=urllib.parse.quote)
        r = c.get(u)
        open(os.path.join(RAW, "diagnostic_%s.xml" % title.split()[0].lower()), "wb").write(r.body or b"")
        res = bne.parse_search(r.body)
        recs = []
        for rec in res["records"]:
            n = bne.normalize_record("0", rec)
            recs.append(dict(record_id=n.record_id, title=n.title, isbns=n.returned_isbns, publisher=n.publisher, published=n.published, language=n.language, series=n.series, volume=n.volume))
        out[title] = dict(total_reported=res["total"], returned=recs)
        print(title, "| total reported", res["total"], "| returned", len(recs))
        for x in recs:
            print("   ", x)
    json.dump(out, open(os.path.join(OUT, "diagnostic.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, "requests.csv"), "a", encoding="utf-8", newline="\n") as f:
        for url, st, secs, err in c.log:
            f.write("diagnostic,%s,%.2f,%s\n" % (st, secs, err or ""))


def run(args):
    fx = fixtures()
    ids = PILOT if args.cmd == "pilot" else [i for i in SPANISH if i not in PILOT]
    records = load_records()
    c = new_client()
    os.makedirs(OUT, exist_ok=True)
    log0 = len(c.log)
    try:
        for fid in ids:
            if "bne|" + fid in records and records["bne|" + fid]["status"] != "REQUEST_FAILED":
                print("reusing", fid)
                continue
            print("[bne]", fid, flush=True)
            n = fetch(c, fx[fid])
            records["bne|" + fid] = n.to_dict()
            print("   ", n.status, n.error or "", "| title:", n.title, "| isbns:", n.returned_isbns, "| candidates:", n.candidates)
    except Blocked as b:
        print("ACCESS_BLOCKED:", b)
        for fid in ids:
            records.setdefault("bne|" + fid, Normalized("bne", SPANISH[fid], status="REQUEST_FAILED", error="ACCESS_BLOCKED: %s" % b).to_dict())
    json.dump(records, open(os.path.join(OUT, "records.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    with open(os.path.join(OUT, "requests.csv"), "a", encoding="utf-8", newline="\n") as f:
        for url, st, secs, err in c.log[log0:]:
            f.write("search,%s,%.2f,%s\n" % (st, secs, (err or "").replace(",", ";")))


def evaluate(fixtures_, records, decisions):
    rows = []
    for fid, f in fixtures_.items():
        rec = records.get("bne|" + fid)
        if rec is None:
            rec = Normalized("bne", f["isbn13"], status="REQUEST_FAILED", error="NOT_EVALUATED: not requested").to_dict()
        cls, why = classify_p1(f, rec)
        if str(rec.get("error") or "").startswith("NOT_EVALUATED"):
            cls = "NOT_EVALUATED"
        d = decisions.get(("bne", fid, "normal"))
        w = decisions.get(("bne", fid, "wrongvol"))
        rows.append(dict(provider="bne", id=fid, category=f["category"], verification=f["verification"], cls=cls, reasons="; ".join(why),
                         shelfos=bridge.decision_for(d) if rec["status"] == "OK" else "INSUFFICIENT_EVIDENCE", tier=d["tier"] if d else None,
                         wrongvol=bridge.decision_for(w) if w else None, requests=rec.get("requests", 0), seconds=rec.get("seconds", 0.0),
                         status=rec["status"], error=rec.get("error"), record=rec))
    return rows


def make_report(args):
    fx = fixtures()
    records = load_records()
    if not records:
        sys.exit("BNE report FAILED: no recorded BNE results (run the pilot first). No report outputs were updated.")
    rows = []
    for key, rec in records.items():
        if rec["status"] == "OK":
            fid = key.split("|", 1)[1]
            for wv in (False, True):
                rows += [["bne:" + r[0]] + r[1:] for r in bridge.rows_for(fx[fid], [rec], wrong_volume=wv)]
    tin, tout = os.path.join(OUT, "bridge_in.tsv"), os.path.join(OUT, "bridge_out.tsv")
    tmp_in, tmp_out = os.path.join(OUT, "bridge_in.tmp.tsv"), os.path.join(OUT, "bridge_out.tmp.tsv")
    extra = []
    try:
        if not rows:
            decisions_raw, ver = {}, dict(mode="no-ok-records", groups=0)       # nothing to score: no matcher decision exists and none is claimed
        elif args.jvm:
            bridge.write_tsv(tmp_in, rows)
            decisions_raw, ver = bridge.run_jvm(tmp_in, tmp_out, os.path.abspath(os.path.join(LAB, "..", "..")))
            ver["mode"] = "fresh-jvm-run"
            extra = [(tmp_in, tin), (tmp_out, tout)]
        else:
            if not os.path.exists(tin):
                raise bridge.BridgeError("the committed BNE bridge input is missing; run report --jvm")
            probe = os.path.join(OUT, "bridge_in.expected.tsv")
            bridge.write_tsv(probe, rows)
            try:
                same = bridge.digest(probe) == bridge.digest(tin)
            finally:
                os.remove(probe)
            if not same:
                raise bridge.BridgeError("the committed BNE bridge input does not match the current records; run report --jvm")
            decisions_raw, ver = bridge.verify(tin, tout)
            ver["mode"] = "committed-snapshot"
        decisions = {}
        for (key, probe_), d in decisions_raw.items():
            prov, fid = key.split(":", 1)
            decisions[(prov, fid, probe_)] = d
        out_rows = evaluate(fx, records, decisions)
        attempted = [(r["id"], r["category"]) for r in out_rows if "bne|" + r["id"] in records]
        sc = {"bne": report.scorecard(out_rows, "bne", attempted)}
        meta = dict(generated=time.strftime("%Y-%m-%d %H:%M:%S"), fixtures=len(fx), evaluated=len(attempted), bridge_verification=ver)
        report.write_outputs(OUT, out_rows, sc, meta, extra)
    except bridge.BridgeError as e:
        sys.exit("BNE report FAILED: matcher snapshot verification failed. No report outputs were updated. Reason: %s" % e)
    finally:
        for t in (tmp_in, tmp_out):
            if os.path.exists(t):
                os.remove(t)
    print(report.markdown("bne", sc["bne"]))
    for r in out_rows:
        print(" ", r["id"], r["status"], r["cls"], r["shelfos"], "|", r["reasons"][:100])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for n in ("explain", "control", "diagnose", "pilot", "full"):
        sub.add_parser(n)
    r = sub.add_parser("report")
    r.add_argument("--jvm", action="store_true")
    a = ap.parse_args()
    {"explain": explain, "control": control, "diagnose": diagnose, "pilot": run, "full": run, "report": make_report}[a.cmd](a)


if __name__ == "__main__":
    main()
