# ShelfOS provider evaluation lab (Phase 4.5C.P0)

A small, read-only research harness. It is **not** part of the Android app, adds no dependency and is
not a production client. Results and conclusions: `docs/research/PHASE_4_5_PROVIDER_EVALUATION.md`.

Requirements: Python 3 (stdlib only). The optional production-matcher step needs the repository's Gradle wrapper.

```
cd tools/provider-lab
python benchmark.py validate                # corpus schema + ISBN checksums (offline)
python -m unittest discover -s tests -t .   # 30 offline tests, no network
python benchmark.py control                 # one bounded Wikidata control query (LIMIT 3)
python benchmark.py run --stage pilot       # live, 8 fixtures, both providers
python benchmark.py run --stage full        # live, all 36 fixtures (about 130 requests)
python benchmark.py run --ids en-gatsby --providers inventaire
python benchmark.py report --jvm            # classify + score with the production MetadataMatcher, write results/
```

Rules baked in: GET only; one request in flight; at least 1.5 s between requests per host; descriptive
User-Agent with the repository URL; 2 MB response cap; limited retries for transient failures only; the provider
is stopped on HTTP 403/429; a transport failure is `REQUEST_FAILED`, never "no result"; no cover or image download;
no `refresh`, `resolve`, `enrich` or any write route; no scraping.

Layout: `fixtures/isbn_corpus.json` (36 verified ISBNs, 7 flagged single-source, plus excluded candidates with
reasons), `providers/` (URL building and parsing), `lab/` (HTTP client, ISBN helpers, classification, bridge,
report), `tests/`, `results/` (normalized records, per-case CSV, summary; raw upstream payloads in `results/raw/`
are git-ignored). The JVM bridge test `ProviderLabBridgeTest` is inert unless `SHELFOS_LAB_IN` and `SHELFOS_LAB_OUT` are set.
