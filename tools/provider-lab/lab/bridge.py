"""Hand normalized provider records to the production ShelfOS matcher and read its decision back.

The matcher is Kotlin (MetadataMatcher, EditionClustering). The lab writes a TSV, runs the JVM test
ProviderLabBridgeTest (unit-test source only, no production code) with SHELFOS_LAB_IN/OUT set, and
reads one decision per (fixture, probe). No matching logic is reimplemented here."""
import os
import subprocess
import urllib.parse

CATEGORY = {"books_en": "BOOK", "manga_ja": "MANGA", "manga_en": "MANGA", "manga_es": "MANGA", "comics_tpb": "COMIC"}


def _q(v):
    return urllib.parse.quote("" if v is None else str(v), safe="")


def _unq(v):
    return urllib.parse.unquote(v)


def rows_for(fx, records, wrong_volume=False):
    """records: Normalized dicts (status OK) for this fixture. One TSV row per record."""
    vol = fx.get("expected_volume")
    single = vol if vol and vol.isdigit() else None
    if wrong_volume:
        if not single:
            return []
        single = str(int(single) + 1)
    out = []
    for r in records:
        out.append([fx["id"], "wrongvol" if wrong_volume else "normal", CATEGORY[fx["category"]], fx["isbn13"], fx["expected_title"],
                    fx["expected_creator"], fx.get("expected_series"), single, fx["expected_language"], fx.get("expected_publisher"),
                    r["provider"], r["record_id"], r.get("title"), (r.get("creators") or [None])[0], r.get("series"), r.get("volume"),
                    r.get("language"), r.get("publisher"), (r.get("published") or "")[:4], ",".join(r.get("returned_isbns") or []),
                    "1" if r.get("cover_present") else "0"])
    return out


def write_tsv(path, rows):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for row in rows:
            f.write("\t".join(_q(c) for c in row) + "\n")


def read_decisions(path):
    """-> {(id, probe): dict(tier, rejected, apply, agreements, contradictions)}"""
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < 7:
                continue
            id_, probe, tier, rejected, apply_, agree, contra = [_unq(x) for x in p[:7]]
            out[(id_, probe)] = dict(tier=tier, rejected=rejected == "true", apply=apply_ == "true",
                                     agreements=[x for x in agree.split(",") if x], contradictions=[x for x in contra.split(",") if x])
    return out


def run_jvm(tsv_in, tsv_out, repo_root, timeout=900):
    env = dict(os.environ, SHELFOS_LAB_IN=os.path.abspath(tsv_in), SHELFOS_LAB_OUT=os.path.abspath(tsv_out))
    gradlew = os.path.join(repo_root, "gradlew.bat" if os.name == "nt" else "gradlew")
    cmd = [gradlew, "--offline", ":app:testDebugUnitTest", "--tests", "*ProviderLabBridgeTest", "--rerun", "--console=plain", "-q"]
    return subprocess.run(cmd, cwd=repo_root, env=env, capture_output=True, text=True, timeout=timeout)


def decision_for(d):
    """Map the matcher result to the lab's ShelfOS outcomes."""
    if d is None:
        return "INSUFFICIENT_EVIDENCE"
    if d["apply"]:
        return "AUTO_ACCEPT"
    if d["rejected"] or d["tier"] == "LOW":
        return "REJECT"
    return "REVIEW"
