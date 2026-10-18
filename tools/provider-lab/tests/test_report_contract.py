"""R-1/R-2 contract tests: the lab never presents an unavailable or invalid matcher result as a measured outcome.
Offline only: temporary directories, the committed snapshot, and a faked Gradle process. No live requests."""
import contextlib
import io
import os
import re
import shutil
import sys
import tempfile
import unittest
import urllib.parse

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import benchmark                         # noqa: E402
from lab import bridge                   # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
COMMITTED = benchmark.RESULTS
SENTINEL = b"previous report - must stay byte-identical\n"
NO_ARGS = type("A", (), dict(jvm=False))
JVM_ARGS = type("A", (), dict(jvm=True))


def read(path):
    with open(path, "rb") as f:
        return f.read()


def write(path, data):
    with open(path, "wb") as f:
        f.write(data)


def q(v):
    return urllib.parse.quote(v, safe="")


class Base(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.d, True)
        for n in ("records.json", "bridge_in.tsv", "bridge_out.tsv"):
            shutil.copy(os.path.join(COMMITTED, n), os.path.join(self.d, n))
        self.saved = benchmark.RESULTS
        benchmark.RESULTS = self.d
        self.addCleanup(setattr, benchmark, "RESULTS", self.saved)
        self.p = lambda n: os.path.join(self.d, n)

    def seed_previous_report(self):
        write(self.p("summary.json"), SENTINEL)
        write(self.p("cases.csv"), SENTINEL)

    def run_report(self, args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            try:
                benchmark.make_report(args)
                code = None
            except SystemExit as e:
                code = e.code
        return code, out.getvalue()

    def assert_failed_closed(self, args=NO_ARGS, reason=None):
        self.seed_previous_report()
        before = {n: read(self.p(n)) for n in os.listdir(self.d)}
        code, out = self.run_report(args())
        self.assertIsInstance(code, str, "must exit non-zero with a diagnostic, got %r" % (code,))
        self.assertIn("matcher snapshot verification failed", code)
        self.assertIn("No report outputs were updated", code)
        if reason:
            self.assertRegex(code, reason)
        self.assertEqual("", out, "nothing may be presented as a measured result")
        self.assertEqual(SENTINEL, read(self.p("summary.json"))); self.assertEqual(SENTINEL, read(self.p("cases.csv")))
        after = {n: read(self.p(n)) for n in os.listdir(self.d)}
        self.assertEqual(before, after, "no file may change or appear (including temporary files)")
        return code

    def edit_out(self, fn):
        lines = read(self.p("bridge_out.tsv")).decode("utf-8").split("\n")
        fn(lines)
        write(self.p("bridge_out.tsv"), "\n".join(lines).encode("utf-8"))

    def set_field(self, line_no, index, value):
        def f(lines):
            cols = lines[line_no].split("\t")
            cols[index] = value
            lines[line_no] = "\t".join(cols)
        self.edit_out(f)


class ValidOffline(Base):
    def test_committed_snapshot_reproduces_the_verified_report(self):
        code, out = self.run_report(NO_ARGS())
        self.assertIsNone(code)
        import json
        s = json.load(open(self.p("summary.json"), encoding="utf-8"))
        v = s["meta"]["bridge_verification"]
        self.assertEqual(("committed-snapshot", 46), (v["mode"], v["groups"])); self.assertTrue(s["meta"]["jvm_bridge"])
        self.assertEqual(read(os.path.join(COMMITTED, "cases.csv")), read(self.p("cases.csv")), "classifications and decisions unchanged")
        inv = s["scorecards"]["inventaire"]["ALL"]
        self.assertEqual((13, 15, 0, 14), (inv["accept"], inv["review"], inv["reject"], inv["wrongvol_n"]))
        self.assertEqual((19, 9, 8), (inv["exact"], inv["partial"], inv["none"]))
        self.assertEqual(4, s["scorecards"]["wikidata"]["ALL"]["exact"])
        self.assertIn("auto-accept", out)
        self.assertEqual([], [n for n in os.listdir(self.d) if n.endswith(".tmp")])


class FailsClosed(Base):
    def test_missing_snapshot_output(self):
        os.remove(self.p("bridge_out.tsv"))
        self.assert_failed_closed(reason="missing")

    def test_missing_snapshot_input(self):
        os.remove(self.p("bridge_in.tsv"))
        self.assert_failed_closed(reason="missing")

    def test_wrong_input_digest(self):
        self.edit_out(lambda lines: lines.__setitem__(0, re.sub(r"\t[0-9a-f]{64}\t", "\t" + "0" * 64 + "\t", lines[0])))
        self.assert_failed_closed(reason="different input")

    def test_input_that_does_not_match_the_records(self):
        with open(self.p("bridge_in.tsv"), "ab") as f:
            f.write(b"x\n")
        self.assert_failed_closed(reason="does not match the current records")

    def test_header_missing_or_foreign(self):
        self.edit_out(lambda lines: lines.pop(0))
        self.assert_failed_closed(reason="header")

    def test_invalid_decision_values_are_rejected_not_coerced(self):
        original = read(self.p("bridge_out.tsv"))
        for index, value, why in ((2, "BOGUS", "tier"), (2, "", "tier"), (2, "very_high", "tier"), (3, "yes", "rejected"), (3, "1", "rejected"), (4, "yes", "apply"),
                                  (4, "0", "apply"), (4, "", "apply"), (4, "True", "apply"), (5, "IDENTIFIER,NOPE", "agreement"), (6, "TITLE,bogus", "contradiction")):
            write(self.p("bridge_out.tsv"), original)
            self.set_field(1, index, q(value) if index < 5 else value)
            self.assert_failed_closed(reason=why)

    def test_missing_decision_fields(self):
        self.edit_out(lambda lines: lines.__setitem__(1, "\t".join(lines[1].split("\t")[:6])))
        self.assert_failed_closed(reason="unparseable")

    def test_malformed_header_counts_are_a_controlled_error_not_a_traceback(self):
        original = read(self.p("bridge_out.tsv"))
        for bad in ("abc", "", "-1", "46.0", "04 6", " 46"):
            write(self.p("bridge_out.tsv"), original)
            self.edit_out(lambda lines: lines.__setitem__(0, "\t".join(lines[0].split("\t")[:3] + [bad])))
            self.assert_failed_closed(reason="malformed bridge header count")

    def test_incomplete_duplicate_and_unexpected_groups(self):
        original = read(self.p("bridge_out.tsv"))
        self.edit_out(lambda lines: lines.pop(5))
        self.assert_failed_closed(reason="missing=1")
        write(self.p("bridge_out.tsv"), original)
        self.edit_out(lambda lines: lines.insert(2, lines[1]))
        self.assert_failed_closed(reason="duplicate=1")
        write(self.p("bridge_out.tsv"), original)
        self.edit_out(lambda lines: lines.insert(2, "\t".join([q("zzz:none"), q("normal")] + lines[1].split("\t")[2:])))
        self.assert_failed_closed(reason="unexpected=1")

    def test_a_failure_while_writing_leaves_the_previous_report_untouched(self):
        from lab import report
        self.seed_previous_report()
        real = report._write_files

        def partial(cases_path, summary_path, *a):
            write(cases_path, b"half written")
            raise OSError("disk full")
        report._write_files = partial
        try:
            with self.assertRaises(OSError):
                self.run_report(NO_ARGS())
        finally:
            report._write_files = real
        self.assertEqual(SENTINEL, read(self.p("summary.json"))); self.assertEqual(SENTINEL, read(self.p("cases.csv")))
        self.assertEqual([], [n for n in os.listdir(self.d) if n.endswith(".tmp")])


class FreshJvm(Base):
    def fake_gradle(self, rc=0, write_output=True, tier="VERY_HIGH"):
        def run(cmd, **k):
            if write_output:
                tin, tout = k["env"]["SHELFOS_LAB_IN"], k["env"]["SHELFOS_LAB_OUT"]
                groups = bridge.expected_groups(tin)
                lines = ["#shelfos-lab-bridge\t1\t%s\t%d" % (bridge.digest(tin), len(groups))]
                lines += ["\t".join([q(a), q(b), tier, "false", "true", "IDENTIFIER", ""]) for a, b in groups]
                write(tout, ("\n".join(lines) + "\n").encode("utf-8"))
            return type("CP", (), dict(returncode=rc, stdout="", stderr="boom"))()
        return run

    def with_gradle(self, fn, **kw):
        real = bridge.subprocess.run
        bridge.subprocess.run = self.fake_gradle(**kw)
        try:
            return self.run_report(JVM_ARGS())
        finally:
            bridge.subprocess.run = real

    def test_successful_fresh_run_is_verified_and_replaces_everything_together(self):
        self.seed_previous_report()
        code, out = self.with_gradle(None)
        self.assertIsNone(code)
        import json
        s = json.load(open(self.p("summary.json"), encoding="utf-8"))
        self.assertEqual(("fresh-jvm-run", 46), (s["meta"]["bridge_verification"]["mode"], s["meta"]["bridge_verification"]["groups"]))
        self.assertEqual([], [n for n in os.listdir(self.d) if ".tmp" in n])
        self.assertEqual(s["meta"]["bridge_verification"]["input_sha256"], bridge.digest(self.p("bridge_in.tsv")))
        bridge.verify(self.p("bridge_in.tsv"), self.p("bridge_out.tsv"))          # the replaced pair verifies

    def test_failing_or_skipped_gradle_is_a_failure_and_changes_nothing(self):
        for kw in (dict(rc=1), dict(rc=0, write_output=False)):
            self.seed_previous_report()
            before = {n: read(self.p(n)) for n in os.listdir(self.d)}
            code, out = self.with_gradle(None, **kw)
            self.assertIsInstance(code, str); self.assertIn("verification failed", code)
            self.assertEqual("", out)
            self.assertEqual(before, {n: read(self.p(n)) for n in os.listdir(self.d)})

    def test_invalid_output_from_gradle_is_rejected(self):
        self.seed_previous_report()
        before = {n: read(self.p(n)) for n in os.listdir(self.d)}
        code, _ = self.with_gradle(None, tier="BOGUS")
        self.assertIn("invalid matcher tier", code)
        self.assertEqual(before, {n: read(self.p(n)) for n in os.listdir(self.d)})


class ProtocolVocabulary(unittest.TestCase):
    """bridge.TIERS / bridge.SIGNALS must be exactly the Kotlin enums the bridge test serializes."""

    def enum(self, relpath, name):
        text = open(os.path.join(REPO, relpath), encoding="utf-8").read()
        body = re.search(r"enum class %s\s*\{([^}]*)\}" % name, text).group(1)
        return tuple(x.strip() for x in body.replace("\n", " ").split(",") if x.strip())

    def test_tiers_and_signals_match_production(self):
        base = "app/src/main/java/com/d4guilar/shelfos/domain/metadata/"
        self.assertEqual(self.enum(base + "PublicationMetadata.kt", "MatchConfidence"), bridge.TIERS)
        self.assertEqual(self.enum(base + "Matching.kt", "MatchSignal"), bridge.SIGNALS)

    def test_the_bridge_test_serializes_names_and_booleans_exactly(self):
        text = open(os.path.join(REPO, "app/src/test/java/com/d4guilar/shelfos/domain/metadata/ProviderLabBridgeTest.kt"), encoding="utf-8").read()
        self.assertIn("e.tier.name", text); self.assertIn("e.rejected.toString()", text); self.assertIn("(ranking.apply != null).toString()", text)
        self.assertIn("it.name", text)


class StrictVerifyUnit(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.d, True)
        self.tin, self.tout = os.path.join(self.d, "in.tsv"), os.path.join(self.d, "out.tsv")
        bridge.write_tsv(self.tin, [["p:a", "normal", "BOOK", "x", "t"]])

    def out(self, agree="IDENTIFIER", contra="", tier="VERY_HIGH", rejected="false", apply="true", count=None, cols=7):
        row = [q("p:a"), q("normal"), tier, rejected, apply, agree, contra][:cols]
        count = 1 if count is None else count
        write(self.tout, ("#shelfos-lab-bridge\t1\t%s\t%s\n%s\n" % (bridge.digest(self.tin), count, "\t".join(row))).encode("utf-8"))

    def test_known_values_and_empty_signal_lists_are_valid(self):
        for tier in ("VERY_HIGH", "MEDIUM", "LOW"):
            for rejected, apply in (("true", "false"), ("false", "false"), ("false", "true")):
                self.out(tier=tier, rejected=rejected, apply=apply, agree="", contra="")
                d, _ = bridge.verify(self.tin, self.tout)
                self.assertEqual((tier, rejected == "true", apply == "true", [], []), tuple(d[("p:a", "normal")][k] for k in ("tier", "rejected", "apply", "agreements", "contradictions")))
        self.out(agree=",".join(bridge.SIGNALS), contra=",".join(reversed(bridge.SIGNALS)))
        d, _ = bridge.verify(self.tin, self.tout)
        self.assertEqual(list(bridge.SIGNALS), d[("p:a", "normal")]["agreements"])

    def test_every_invalid_value_raises_bridge_error(self):
        cases = [dict(tier="BOGUS"), dict(tier=""), dict(tier="high"), dict(rejected="yes"), dict(rejected="no"), dict(rejected="1"), dict(rejected=""),
                 dict(apply="yes"), dict(apply="0"), dict(apply="TRUE"), dict(agree="IDENTIFIER,BOGUS"), dict(contra="nope"), dict(agree="identifier"),
                 dict(count="x"), dict(count="1.0"), dict(count="-1"), dict(cols=6), dict(cols=5)]
        for kw in cases:
            self.out(**kw)
            with self.assertRaises(bridge.BridgeError, msg=str(kw)):
                bridge.verify(self.tin, self.tout)


if __name__ == "__main__":
    unittest.main()
