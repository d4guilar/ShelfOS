"""Hand normalized provider records to the production ShelfOS matcher and read its decision back.

The matcher is Kotlin (MetadataMatcher, EditionClustering). The lab writes a TSV, runs the JVM test
ProviderLabBridgeTest (unit-test source only, no production code) with SHELFOS_LAB_IN/OUT set, and
reads one decision per (fixture, probe). No matching logic is reimplemented here."""
import hashlib
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


class BridgeError(Exception):
    """The production-matcher bridge did not produce a verifiable result. Never converted into 'insufficient evidence'."""


def digest(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def expected_groups(tsv_in):
    """The exact (fixture-key, probe) groups the matcher must answer for this input."""
    groups = []
    with open(tsv_in, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                p = line.rstrip("\n").split("\t")
                g = (_unq(p[0]), _unq(p[1]))
                if g not in groups:
                    groups.append(g)
    return groups


def verify(tsv_in, tsv_out):
    """Decisions for tsv_in, or BridgeError: missing/unparseable output, digest mismatch (stale or foreign output),
    and missing, duplicate or unexpected groups are all rejected. Returns (decisions, metadata)."""
    if not os.path.exists(tsv_out):
        raise BridgeError("bridge output is missing (the JVM test was skipped or did not run)")
    lines = open(tsv_out, encoding="utf-8").read().splitlines()
    if not lines or not lines[0].startswith("#shelfos-lab-bridge\t"):
        raise BridgeError("bridge output has no verification header (stale or foreign file)")
    head = lines[0].split("\t")
    if len(head) != 4 or head[1] != "1":
        raise BridgeError("unsupported bridge output header")
    want = digest(tsv_in)
    if head[2] != want:
        raise BridgeError("bridge output was produced from a different input (digest %s != %s)" % (head[2][:12], want[:12]))
    seen = []
    for line in lines[1:]:
        if not line.strip():
            continue
        p = line.split("\t")
        if len(p) < 7:
            raise BridgeError("unparseable bridge line: %r" % line[:80])
        seen.append((_unq(p[0]), _unq(p[1])))
    expected = expected_groups(tsv_in)
    dup = sorted({g for g in seen if seen.count(g) > 1})
    missing = [g for g in expected if g not in seen]
    extra = [g for g in dict.fromkeys(seen) if g not in expected]
    if dup or missing or extra or len(seen) != int(head[3]):
        raise BridgeError("bridge coverage mismatch: missing=%d duplicate=%d unexpected=%d declared=%s seen=%d"
                          % (len(missing), len(dup), len(extra), head[3], len(seen)))
    return read_decisions(tsv_out), dict(input_sha256=want, groups=len(expected), contract=head[1])


def read_decisions(path):
    """-> {(id, probe): dict(tier, rejected, apply, agreements, contradictions)}"""
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < 7 or p[0].startswith("#"):
                continue
            id_, probe, tier, rejected, apply_, agree, contra = [_unq(x) for x in p[:7]]
            out[(id_, probe)] = dict(tier=tier, rejected=rejected == "true", apply=apply_ == "true",
                                     agreements=[x for x in agree.split(",") if x], contradictions=[x for x in contra.split(",") if x])
    return out


def run_jvm(tsv_in, tsv_out, repo_root, timeout=900):
    """Runs the production-matcher bridge and returns the verified decisions. Any failure raises BridgeError: a previous
    output is deleted first, a non-zero Gradle exit is an error, and a skipped test leaves no output, which verify() rejects."""
    for stale in (tsv_out,):
        if os.path.exists(stale):
            os.remove(stale)
    env = dict(os.environ, SHELFOS_LAB_IN=os.path.abspath(tsv_in), SHELFOS_LAB_OUT=os.path.abspath(tsv_out))
    gradlew = os.path.join(repo_root, "gradlew.bat" if os.name == "nt" else "gradlew")
    cmd = [gradlew, "--offline", ":app:testDebugUnitTest", "--tests", "*ProviderLabBridgeTest", "--rerun", "--console=plain", "-q"]
    try:
        cp = subprocess.run(cmd, cwd=repo_root, env=env, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError) as e:
        raise BridgeError("could not run the JVM bridge: %s" % e)
    if cp.returncode != 0:
        raise BridgeError("Gradle exited %d: %s" % (cp.returncode, (cp.stdout + cp.stderr)[-300:].strip()))
    return verify(tsv_in, tsv_out)


def decision_for(d):
    """Map the matcher result to the lab's ShelfOS outcomes."""
    if d is None:
        return "INSUFFICIENT_EVIDENCE"
    if d["apply"]:
        return "AUTO_ACCEPT"
    if d["rejected"] or d["tier"] == "LOW":
        return "REJECT"
    return "REVIEW"
