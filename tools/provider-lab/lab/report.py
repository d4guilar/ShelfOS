"""Metrics and Markdown/CSV/JSON output. Every percentage is printed with its numerator/denominator."""
import csv
import json
import statistics

from lab import bridge, classify

CATS = ["books_en", "manga_ja", "manga_en", "manga_es", "comics_tpb"]
CAT_LABEL = {"books_en": "English books", "manga_ja": "Manga (ja)", "manga_en": "Manga (en)", "manga_es": "Manga (es)", "comics_tpb": "Comic TPB / collected"}
COMPLETE_FIELDS = ["title", "creators", "publisher", "language", "series", "volume", "published", "cover_present"]


def frac(n, d):
    return "%d/%d (%.0f%%)" % (n, d, 100.0 * n / d) if d else "0/0 (n/a)"


def evaluate(fixtures, records, decisions):
    """records: {(provider, fixture_id): dict}; decisions: {(provider, id, probe): dict}.
    Returns per-case rows."""
    rows = []
    for fx in fixtures:
        for (prov, fid), rec in sorted(records.items()):
            if fid != fx["id"]:
                continue
            cls, why = classify.classify(fx, rec)
            d = decisions.get((prov, fx["id"], "normal"))
            w = decisions.get((prov, fx["id"], "wrongvol"))
            rows.append(dict(provider=prov, id=fx["id"], category=fx["category"], verification=fx["verification"], cls=cls, reasons="; ".join(why),
                             shelfos=bridge.decision_for(d) if rec["status"] == "OK" else "INSUFFICIENT_EVIDENCE",
                             tier=d["tier"] if d else None, wrongvol=bridge.decision_for(w) if w else None,
                             requests=rec.get("requests", 0), seconds=rec.get("seconds", 0.0), status=rec["status"], error=rec.get("error"),
                             record=rec))
    return rows


def scorecard(rows, provider, attempted_ids):
    out = {}
    for cat in CATS + ["ALL"]:
        rs = [r for r in rows if r["provider"] == provider and (cat == "ALL" or r["category"] == cat)]
        att = [i for i in attempted_ids if cat == "ALL" or i[1] == cat]
        n = len(att)
        c = lambda k: sum(1 for r in rs if r["cls"] == k)
        ok = [r for r in rs if r["status"] == "OK"]
        http_ok = sum(1 for r in rs if r["status"] in ("OK", "NO_RESULT"))
        comp = {f: sum(1 for r in ok if _has(r["record"], f)) for f in COMPLETE_FIELDS}
        secs = [r["seconds"] for r in rs if r["seconds"]]
        out[cat] = dict(attempted=n, http_ok=http_ok, ok=len(ok), exact=c("EXACT_EDITION"), partial=c("PARTIAL_EDITION"), ambiguous=c("AMBIGUOUS"),
                        wrong=c("WRONG_EDITION"), conflict=c("CONFLICTING_VOLUME"), none=c("NO_RESULT"), failed=c("REQUEST_FAILED"),
                        requests=sum(r["requests"] for r in rs), complete=comp, covers=comp["cover_present"],
                        accept=sum(1 for r in rs if r["shelfos"] == "AUTO_ACCEPT"), review=sum(1 for r in rs if r["shelfos"] == "REVIEW"),
                        reject=sum(1 for r in rs if r["shelfos"] == "REJECT"),
                        wrongvol_accept=sum(1 for r in rs if r["wrongvol"] == "AUTO_ACCEPT"), wrongvol_n=sum(1 for r in rs if r["wrongvol"]),
                        lat_median=statistics.median(secs) if secs else None,
                        lat_p95=(sorted(secs)[max(0, int(round(0.95 * len(secs))) - 1)] if len(secs) >= 20 else None), lat_n=len(secs))
    return out


def _has(rec, f):
    v = rec.get(f)
    return bool(v)


def markdown(provider, sc):
    lines = ["| Category | Attempted | Reached (HTTP ok) | Records | Exact edition | Partial | Ambiguous | Wrong ed. | Conflict vol. | No result | Failed | ShelfOS auto-accept | Review | Reject | Wrong-volume probe auto-accepted | Cover ref | Requests |",
             "|" + "---|" * 17]
    for cat in CATS + ["ALL"]:
        s = sc[cat]
        label = CAT_LABEL.get(cat, "**All**")
        n = s["attempted"]
        lines.append("| %s | %d | %s | %s | %s | %d | %d | %d | %d | %d | %d | %s | %d | %d | %s | %s | %d |" % (
            label, n, frac(s["http_ok"], n), frac(s["ok"], n), frac(s["exact"], n), s["partial"], s["ambiguous"], s["wrong"], s["conflict"], s["none"], s["failed"],
            frac(s["accept"], n), s["review"], s["reject"], frac(s["wrongvol_accept"], s["wrongvol_n"]), frac(s["covers"], s["ok"]), s["requests"]))
    return "\n".join(lines)


def completeness_md(sc):
    s = sc["ALL"]
    return "; ".join("%s %s" % (f, frac(s["complete"][f], s["ok"])) for f in COMPLETE_FIELDS)


def write_outputs(outdir, rows, scorecards, meta):
    with open(outdir + "/cases.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["provider", "fixture", "category", "verification", "class", "shelfos_decision", "matcher_tier", "wrongvol_decision", "requests", "seconds",
                    "status", "returned_isbns", "title", "language", "volume", "series", "publisher", "cover_ref", "reasons", "error"])
        for r in rows:
            rec = r["record"]
            w.writerow([r["provider"], r["id"], r["category"], r["verification"], r["cls"], r["shelfos"], r["tier"], r["wrongvol"], r["requests"], "%.2f" % r["seconds"],
                        r["status"], "|".join(rec["returned_isbns"]), rec.get("title"), rec.get("language"), rec.get("volume"), rec.get("series"), rec.get("publisher"),
                        rec.get("cover_present"), r["reasons"], r["error"]])
    slim = [{k: v for k, v in r.items() if k != "record"} for r in rows]
    with open(outdir + "/summary.json", "w", encoding="utf-8") as f:
        json.dump(dict(meta=meta, scorecards=scorecards, cases=slim), f, ensure_ascii=False, indent=1)
