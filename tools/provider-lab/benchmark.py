#!/usr/bin/env python3
"""ShelfOS provider evaluation lab (Phase 4.5C.P0). Read-only research tool; not part of the app.

  python benchmark.py validate                       Stage A: corpus schema + checksums (offline)
  python benchmark.py run --stage pilot|full         Stages B/C: live read-only requests (opt-in)
  python benchmark.py run --ids a,b --providers inventaire
  python benchmark.py control                        one bounded Wikidata control query (LIMIT 3)
  python benchmark.py report [--jvm]                 classify, score with the production matcher, write results/
"""
import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from lab import bridge, isbn as I, report                      # noqa: E402
from lab.http import Client, Blocked                          # noqa: E402
from providers import inventaire, wikidata                    # noqa: E402

CORPUS = os.path.join(HERE, "fixtures", "isbn_corpus.json")
RESULTS = os.path.join(HERE, "results")
RAW = os.path.join(RESULTS, "raw")
PILOT = ["en-gatsby", "en-hp1", "ja-deathnote1", "en-onepiece1", "es-onepiece1", "tpb-watchmen", "ja-aot1", "en-aot1"]   # 8 fixtures, every required kind
REQUIRED = ["id", "isbn13", "expected_title", "expected_creator", "expected_language", "expected_publisher", "expected_series", "expected_volume",
            "edition_type", "source_urls", "verified_on", "verification", "category"]
TARGET = {"books_en": 12, "manga_ja": 6, "manga_en": 6, "manga_es": 6, "comics_tpb": 6}


def load():
    return json.load(open(CORPUS, encoding="utf-8"))


def validate(corpus=None):
    corpus = corpus or load()
    errs = []
    seen = set()
    counts = {}
    for f in corpus["fixtures"]:
        for k in REQUIRED:
            if k not in f:
                errs.append("%s: missing %s" % (f.get("id"), k))
        if not I.check13(f["isbn13"]):
            errs.append("%s: bad ISBN-13 checksum %s" % (f["id"], f["isbn13"]))
        if f["isbn13"] in seen or f["id"] in seen:
            errs.append("%s: duplicate" % f["id"])
        seen.update([f["isbn13"], f["id"]])
        if not f["source_urls"] or not all(u.startswith("https://") for u in f["source_urls"]):
            errs.append("%s: needs https source URLs" % f["id"])
        if f["verification"] not in ("multi-source", "single-source"):
            errs.append("%s: bad verification" % f["id"])
        counts[f["category"]] = counts.get(f["category"], 0) + 1
    for c, n in TARGET.items():
        if counts.get(c, 0) != n:
            errs.append("category %s has %d fixtures, target %d" % (c, counts.get(c, 0), n))
    return errs, counts


def run(args):
    corpus = load()
    errs, _ = validate(corpus)
    if errs:
        sys.exit("corpus invalid: " + "; ".join(errs))
    by_id = {f["id"]: f for f in corpus["fixtures"]}
    ids = PILOT if args.stage == "pilot" else (args.ids.split(",") if args.ids else list(by_id))
    if args.stage == "pilot" and len(ids) > 8:
        sys.exit("pilot is capped at 8 fixtures")
    provs = args.providers.split(",")
    os.makedirs(RAW, exist_ok=True)
    path = os.path.join(RESULTS, "records.json")
    records = json.load(open(path, encoding="utf-8")) if os.path.exists(path) and args.append else {}
    log = []
    for prov in provs:
        client = Client(inventaire.HOST if prov == "inventaire" else wikidata.HOST, min_interval=args.interval, max_requests=args.max_requests,
                        accept="application/json" if prov == "inventaire" else "application/sparql-results+json")
        for fid in ids:
            fx = by_id[fid]
            print("[%s] %s" % (prov, fid), flush=True)
            try:
                if prov == "inventaire":
                    n, raw = inventaire.fetch(client, fx["isbn13"])
                else:
                    r = client.get(wikidata.url(fx["isbn13"]))
                    if r.ok:
                        n = wikidata.parse(fx["isbn13"], r.body)
                    else:
                        n = wikidata.Normalized("wikidata", fx["isbn13"], status="REQUEST_FAILED", error=r.error)
                    n.requests, n.seconds, raw = 1, r.elapsed, r.body
                open(os.path.join(RAW, "%s_%s.json" % (prov, fid)), "wb").write(raw or b"")
            except Blocked as b:
                print("  STOPPED %s: %s" % (prov, b))
                for rest in ids[ids.index(fid):]:
                    records["%s|%s" % (prov, rest)] = dict(wikidata.Normalized(prov, by_id[rest]["isbn13"], status="REQUEST_FAILED", error="provider stopped: %s" % b).to_dict())
                break
            records["%s|%s" % (prov, fid)] = n.to_dict()
        log += [(prov,) + x for x in client.log]
    os.makedirs(RESULTS, exist_ok=True)
    json.dump(records, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    with open(os.path.join(RESULTS, "requests.csv"), "a" if args.append else "w", encoding="utf-8") as f:
        for p, url, st, secs, err in log:
            f.write("%s,%s,%s,%.2f,%s\n" % (p, url.split("?")[0], st, secs, err or ""))
    print("requests: " + ", ".join("%s=%d" % (p, sum(1 for l in log if l[0] == p)) for p in provs))


def control(_):
    c = Client(wikidata.HOST, min_interval=2.0, accept="application/sparql-results+json")
    import urllib.parse
    q = "SELECT ?item ?isbn WHERE { ?item wdt:P212 ?isbn } LIMIT 3"
    r = c.get(wikidata.BASE + "?" + urllib.parse.urlencode({"query": q, "format": "json"}))
    print(r.status, r.error, r.body[:600].decode("utf-8", "replace"))


def make_report(args):
    corpus = load()
    fixtures = corpus["fixtures"]
    recs = json.load(open(os.path.join(RESULTS, "records.json"), encoding="utf-8"))
    records = {tuple(k.split("|", 1)): v for k, v in recs.items()}
    by_id = {f["id"]: f for f in fixtures}
    decisions = {}
    if args.jvm:
        rows = []
        for (prov, fid), rec in records.items():
            if rec["status"] != "OK":
                continue
            fx = by_id[fid]
            for wv in (False, True):
                rows += [[prov + ":" + r[0]] + r[1:] for r in bridge.rows_for(fx, [rec], wrong_volume=wv)]
        bridge.write_tsv(os.path.join(RESULTS, "bridge_in.tsv"), rows)
        cp = bridge.run_jvm(os.path.join(RESULTS, "bridge_in.tsv"), os.path.join(RESULTS, "bridge_out.tsv"), os.path.abspath(os.path.join(HERE, "..", "..")))
        print("JVM bridge exit", cp.returncode, (cp.stdout + cp.stderr)[-400:])
    out = os.path.join(RESULTS, "bridge_out.tsv")
    if os.path.exists(out):
        for (key, probe), d in bridge.read_decisions(out).items():
            prov, fid = key.split(":", 1)
            decisions[(prov, fid, probe)] = d
    rows = report.evaluate(fixtures, records, decisions)
    meta = dict(generated=time.strftime("%Y-%m-%d %H:%M:%S"), fixtures_in_corpus=len(fixtures), jvm_bridge=os.path.exists(out))
    scs = {}
    for prov in sorted({p for p, _ in records}):
        attempted = [(i, by_id[i]["category"]) for (p, i) in records if p == prov]
        scs[prov] = report.scorecard(rows, prov, attempted)
        print("\n## " + prov + "\n" + report.markdown(prov, scs[prov]) + "\n" + report.completeness_md(scs[prov]))
    report.write_outputs(RESULTS, rows, scs, meta)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("validate")
    sub.add_parser("control")
    r = sub.add_parser("run")
    r.add_argument("--stage", choices=["pilot", "full", "custom"], default="custom")
    r.add_argument("--ids")
    r.add_argument("--providers", default="inventaire,wikidata")
    r.add_argument("--interval", type=float, default=1.5, help="minimum seconds between requests per host")
    r.add_argument("--max-requests", type=int, default=150)
    r.add_argument("--append", action="store_true")
    p = sub.add_parser("report")
    p.add_argument("--jvm", action="store_true", help="score with the production matcher through Gradle")
    a = ap.parse_args()
    if a.cmd == "validate":
        errs, counts = validate()
        print(counts)
        print("OK" if not errs else "\n".join(errs))
        sys.exit(1 if errs else 0)
    {"run": run, "control": control, "report": make_report}[a.cmd](a)


if __name__ == "__main__":
    main()
