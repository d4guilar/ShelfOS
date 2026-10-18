# Phase 4.5C.P0 — Inventaire vs Wikidata provider evaluation

Status: **research spike, read-only, executed 2026-10-18.** Not a production integration. It authorizes no
network permission, provider registration, dependency, cover download or cover storage in the app. Every
Open Library gate (D1, D31) and every earlier provider decision (BOOK_PROVIDERS.md, D22–D31) stands unchanged.

Harness: [`tools/provider-lab/`](../../tools/provider-lab/README.md). Raw upstream payloads were kept
locally and are not committed; the normalized records, per-case table and summary are in
`tools/provider-lab/results/`.

## 1. Executive summary

- **Corpus.** 36 real ISBN-13 fixtures (12 English books, 6 each of Japanese, English and Spanish Manga,
  6 comic/collected editions), every ISBN check-digit valid and checked against catalogs or retailers, never
  against the providers. 29 have two or more sources; **7 rest on a single credible source** (flagged in the
  data). 10 further candidates failed or were surplus and are listed as excluded, not measured.
  **Ground-truth strength matters for the misses:** of the 7 single-source fixtures, **6 were no-result** in
  Inventaire (2 Japanese: Slam Dunk, Chainsaw Man 2; **all 4 single-source Spanish**) and 1 was partial (Fullmetal
  Alchemist JA). A miss against a single-source ISBN is weaker evidence than a miss against a corroborated one
  (see §8 and §16).
- **Inventaire.** Answered 36/36 requests (no errors, no throttling). Returned a record for **28/36 (78%)**
  ISBNs and a fully matching edition for **19/36 (53%)** under the lab's strict ground-truth rules; 9 more were
  partial, 8 had no record. **Spanish Manga: 0/6** (4 of those 6 fixtures are single-source; the 2 independently corroborated Spanish
  fixtures also returned nothing, 0/2). The production `MetadataMatcher` auto-accepted 13/36, sent
  15 to review and rejected none; a deliberately wrong volume was **never** auto-accepted (0/14 probes).
- **Wikidata.** Answered 36/36 requests. Returned a record for **4/36 (11%)**, all English books, all exact and
  all auto-accepted by the matcher; **0/30 for Manga and comics**, and none of the 4 carries a cover reference.
  The query path was proven with a positive control (a known Wikidata edition was found).
- **Independence.** The two are not independent: Inventaire builds on Wikidata and on Open Library,
  national-library endpoints and a closed "dataseed" web service (its own wiki). 25 of its 28 records link an
  Open Library edition id and 22 a Goodreads id. It is **not** a second opinion that is free of the Open Library
  question.
- **Covers.** 26/28 Inventaire records carry an Inventaire-hosted image reference; its OpenAPI example shows
  an `image` taken from `covers.openlibrary.org`. No image rights statement was found. **No cover bytes were
  downloaded and none should be stored on this evidence.**
- **Recommendation: CONDITIONAL GO** for Inventaire as an *optional, metadata-only, secondary* candidate behind
  its own approval gate; **NO-GO** for Wikidata as a standalone provider (coverage) and for any cover use of
  either. Neither replaces Open Library (D1/D31) or a rights-clean cover source. Details in §13–§15.

## 2. Providers and verified documentation

| | Inventaire | Wikidata (Query Service) |
|---|---|---|
| Endpoint used | `GET https://inventaire.io/api/entities/by-uris?uris=isbn:<isbn-13>` and the same route for related `wd:`/`inv:` URIs, `attributes=labels\|claims` | `GET https://query.wikidata.org/sparql?query=…&format=json` |
| Docs | [API spec (Swagger UI)](https://api.inventaire.io/), machine-readable [api_specs.json](https://inventaire.io/public/api_specs.json) v5.1.1, [Entities data](https://wiki.inventaire.io/wiki/Entities_data), [data.inventaire.io](https://data.inventaire.io/) | [Data access](https://www.wikidata.org/wiki/Help:Data_access), [Query Service manual](https://www.mediawiki.org/wiki/Wikidata_Query_Service/User_Manual), [Licensing](https://www.wikidata.org/wiki/Wikidata:Licensing) |
| Auth | none (`public` endpoints) | none |
| Stated rules | Unique, contactable `User-Agent`; rate limiting "enforced"; on HTTP 429 with a legitimate use case contact hello@inventaire.io; breaking changes in the server CHANGELOG; deprecation `warnings` in bodies. **No numeric limit published** | Descriptive `User-Agent` (Foundation policy: blocked without notice otherwise); 429 + `Retry-After`; 60 s query timeout; 60 s processing per 60 s per client; 30 error queries/min; 5 parallel queries/IP; "interfaces not guaranteed stable"; a Wikibase REST API is intended to replace the Action API; Query Service backend migration notes exist |
| Data licence | CC0 for Inventaire's own entities (dumps contain only those); server code AGPL-3.0 | CC0 for main/Property/Lexeme namespaces |
| Write/refresh | Spec lists `refresh` (non-cached data) and POST `entities/resolve`/`enrich`; **none used** | SPARQL SELECT only |

Documentation uncertainty (not assumed away): the spec describes `isbn:` as a pseudo-URI that "includes"
ISBNs but does not state whether a GET for an unknown ISBN makes the server query external sources or seed
records; the Inventaire wiki says it queries national-library endpoints and Open Library and uses a closed
`dataseed` service. I did not call `refresh`, `resolve`, `enrich` or any write route. One earlier probe used the
legacy `?action=by-uris` form before I had read the spec; it was a single HEAD request. The Terms of use,
Legal notice, Privacy and General disclaimer pages exist per the wiki but I did not read their bodies, so
**Inventaire's terms of use, commercial-use position and image policy are unverified.**

## 3. Methodology

- Harness is a stdlib Python 3 CLI. Stage A (offline): corpus schema/checksums, parsers, malformed input,
  HTTP 403/429/`Retry-After`/budget handling, CSV/JSON output — 42 tests (30 original + 12 added for bridge verification and edition selection), all passing, no network.
- Stage B (pilot): 8 fixtures (English book, Japanese, English and Spanish Manga, collected edition) on both providers;
  stopped-if-throttled client, one request in flight, ≥ 1.5 s between requests, descriptive User-Agent with the
  repository URL, 2 MB response cap, retries only for transient failures.
- Stage C (full): all 36 fixtures, same records for both providers. A first full run was lost to a local network
  outage (194 DNS failures); the harness recorded them as `REQUEST_FAILED`, not "no result", and I re-ran the
  whole stage when connectivity returned. Only the clean run is reported.
- Provider data is normalized to a neutral record; unknown stays unknown (no default volume, no language
  inferred, no translation). Edition class comes from comparing with ground truth (lab rules). The ShelfOS
  decision comes only from the **production** `MetadataMatcher`/`EditionClustering`, called through a unit-test
  bridge (`ProviderLabBridgeTest`, inert unless two environment variables are set). The query models a library
  item that already knows the expected ISBN, title, creator, series, volume, language and publisher as embedded
  (non-inferred) metadata — a strict test.
- Requests actually sent (this spike): Inventaire 92 (full) + 22 (pilot) + 5 (probes: 1 HEAD, 4 GET) = 119; Wikidata 36
  (full) + 8 (pilot) + 3 (probe, bounded control, positive control) = 47. **Verified against the committed artifacts:**
  `results/requests.csv` holds exactly 92 Inventaire and 36 Wikidata rows (the final full run) and
  `results/requests_pilot.csv` 22 and 8. The remaining 5 Inventaire and 3 Wikidata requests were probes and controls made
  before the harness logged them; **their URLs were not retained, so request tracing is not complete for them.** The
  aborted run (`requests_full_aborted_dns.csv`, 216 rows) never reached a server. All returned HTTP 200 except the aborted DNS run (which
  never reached a server). No 403/429 occurred.

## 4. Corpus coverage

| Category | Target | Verified & measured | Single-source | Notes |
|---|---:|---:|---:|---|
| English books | 12 | 12 | 0 | two ISBNs have ambiguous printings (1984, Catcher in the Rye) |
| Manga (ja) | 6 | 6 | 3 | |
| Manga (en) | 6 | 6 | 0 | includes an omnibus (Berserk Deluxe vol. 1 = manga vols. 1–3) |
| Manga (es) | 6 | 6 | 4 | includes an omnibus (Maximum Berserk) and volume 2 of Akira |
| Comic TPB / collected | 6 | 6 | 0 | |
| **Total** | **36** | **36** | **7** | `fixtures/isbn_corpus.json`, 67 source URLs |

Adversarial pairs present: One Piece vol. 1 in EN/ES and Attack on Titan vol. 1 in EN/JA/ES (same work, different
editions); Naruto and Chainsaw Man volumes; single volume vs omnibus (Berserk Deluxe, Maximum Berserk);
Chainsaw Man 2 and Akira 2 against volume 1. Excluded and **not measured**: Japanese One Piece / Naruto / Dragon Ball
(ISBN could not be tied to volume 1), Spanish Death Note / Fullmetal Alchemist / Spy x Family (no ISBN found),
two verified but surplus books, and one ISBN supplied from memory that failed verification. They are listed in the
corpus file with reasons. Weakest evidence: single-source fixtures (listed in the corpus).

## 5. Results by category (n = fixtures attempted; every fraction is numerator/denominator)

### Inventaire

| Category | Reached | Records | Exact edition | Partial | No record | Failed | ShelfOS auto-accept | Review | Reject | Wrong-vol. probe auto-accepted | Cover ref |
|---|---|---|---|---|---|---|---|---|---|---|---|
| English books | 12/12 | 12/12 | 12/12 | 0 | 0 | 0 | 9/12 | 3 | 0 | 0/2 | 10/12 |
| Manga (ja) | 6/6 | 4/6 | 2/6 | 2 | 2 | 0 | 0/6 | 4 | 0 | 0/4 | 4/4 |
| Manga (en) | 6/6 | 6/6 | 3/6 | 3 | 0 | 0 | 2/6 | 4 | 0 | 0/5 | 6/6 |
| Manga (es) | 6/6 | **0/6** | 0/6 | 0 | 6 | 0 | 0/6 | 0 | 0 | — | — |
| Comic TPB | 6/6 | 6/6 | 2/6 | 4 | 0 | 0 | 2/6 | 4 | 0 | 0/3 | 6/6 |
| **All** | 36/36 | 28/36 | 19/36 | 9 | 8 | 0 | 13/36 | 15 | 0 | 0/14 | 26/28 |

"Reached" is HTTP success, not edition correctness. Requests per returned record: 92/28 = 3.3 (a returned edition costs up to three requests for the edition, its
work/publisher and its authors/series; a miss costs one).

**What the 1.44 s means.** It is the median, per ISBN, of the **summed HTTP request durations** (observed network and
server time, p95 1.61 s, n = 36). It **excludes** the harness's deliberate pacing (at least 1.5 s between requests
to the host). It is therefore neither end-user waiting time nor total lookup time. Three quantities are distinct:
(1) observed request latency (above); (2) rate-limit pacing, a policy choice of at least 1.5 s per request after the
first; (3) estimated elapsed lookup time, about the summed latency plus pacing, roughly 4 to 6 s for a three-request
hit at the lab's pacing and about 1.5 s for a one-request miss. The estimate was not measured end to end. Misses
show lower request counts because no related-entity requests follow.

### Wikidata

| Category | Reached | Records | Exact edition | No record | Failed | ShelfOS auto-accept | Cover ref |
|---|---|---|---|---|---|---|---|
| English books | 12/12 | 4/12 | 4/12 | 8 | 0 | 4/12 | 0/4 |
| Manga (ja) | 6/6 | 0/6 | 0/6 | 6 | 0 | 0/6 | — |
| Manga (en) | 6/6 | 0/6 | 0/6 | 6 | 0 | 0/6 | — |
| Manga (es) | 6/6 | 0/6 | 0/6 | 6 | 0 | 0/6 | — |
| Comic TPB | 6/6 | 0/6 | 0/6 | 6 | 0 | 0/6 | — |
| **All** | 36/36 | 4/36 | 4/36 | 32 | 0 | 4/36 | 0/4 |

Latency: median 0.36 s, p95 1.69 s (n = 36), one request per ISBN. The four hits (1984, Pride and Prejudice, Atomic
Habits, A Brief History of Time) are exact; none has a series, volume or cover.

Field completeness over returned records — Inventaire (28): title 28, creators 27, language 28, published 25, publisher
**10**, series 14 (14/18 where a series was expected), volume present on 12 (the 12/16 below mixes meanings; see
the volume breakdown), cover 26.

**Volume breakdown (Inventaire, 28 records; 16 fixtures expected a volume).** Single editions: a volume was returned for
10/11 and **all 10 returned were correct** (Fullmetal Alchemist JA had none). Other single-number expectations (collected
editions, expected "1"): Saga returned 1 (correct); Sandman and Ms. Marvel returned none. Together the 14 fixtures with a
single expected number gave 11 returned, 11 correct, 3 missing (**11/14**). The 2 range fixtures are different: Berserk Deluxe
(omnibus, expected "1–3") returned `1`, which is the Deluxe edition's own numbering, **not** the contained original volumes,
and Watchmen (expected "1–12") returned none. The wider **12/16** adds Berserk Deluxe's `1` and is **not** a correct-volume
rate: it counts a field being present, and one present value is the omnibus's own number. Edition numbering, omnibus
numbering, contained-volume ranges and missing volume evidence are four different things the data does not separate. The
work/series payload behind the related-entity requests was not retained, so which entity supplied a given volume cannot be
reconstructed afterwards.
Wikidata (4): title 4, creators 4, language 4, published 3, publisher 1, series 0, volume 0, cover 0.

Sample sizes are 6 per category; percentages by category are indicative only and none supports a coverage claim
beyond this corpus.

## 6. Exact-edition matching with the production matcher

The matcher never saw a wrong-edition ISBN here (neither provider returned one); it separated degrees of certainty:
- 13 Inventaire + 4 Wikidata records were VERY_HIGH and would be applied automatically.
- 15 Inventaire records were MEDIUM → review, and in every case the matcher recorded at least one **contradicting field**
  (the ISBN always agreed): TITLE in 11 (series-name fragments such as "Saga", "Batman"; English or French wording on
  Japanese editions), PUBLISHER in 7 (e.g. HarperCollins vs HarperOne, "Kodansha USA Publishing, LLC" vs "Kodansha Comics"),
  SERIES in 4 (a Japanese label on the English Harry Potter edition, "Robert Langdon series", "Attack on Titan 1"),
  CREATOR in 5 (four Japanese fixtures: the test query used Japanese names and Inventaire returned Latin script, and the matcher does no transliteration; plus Ms. Marvel)
  and VOLUME in 3 (no volume returned).
- Wrong-volume probe (query volume = expected + 1, same candidate): **0/14 auto-accepted** for Inventaire — 11 hard-rejected by
  the volume rule, 3 sent to review because the record carried no volume to contradict.

## 7. Notable false positives and false negatives

No wrong-edition or conflicting-volume record was returned for any fixture, so there are no measured false positives at
the ISBN level. Notable weaknesses:
- **False negatives:** all 6 Spanish ISBNs in Inventaire; 2 Japanese (Slam Dunk new edition, Chainsaw Man vol. 2);
  32/36 in Wikidata.
- **Misleading metadata, correct ISBN:** *Attack on Titan* (Japanese ISBN) returns an English title and shares one work
  with the English edition; *Death Note* (Japanese) returns "Death Note Tome 1"; the series-level titles "Saga",
  "Batman", "Ms. Marvel" lack the volume/subtitle. These are classified partial and the matcher sends them to review.
- **Collections:** Inventaire reports Berserk Deluxe (manga vols. 1–3) as a plain "volume 1" — the omnibus/collection
  distinction is not carried by the data, so a volume-1 query would see a compatible volume.

## 8. Manga EN / JP / ES

| | Records | Exact | Partial | Missing |
|---|---|---|---|---|
| Inventaire EN | 6/6 | 3 | 3 | 0 |
| Inventaire JA | 4/6 | 2 | 2 | 2 |
| Inventaire ES | 0/6 | 0 | 0 | 6 |
| Wikidata (any language) | 0/18 | 0 | 0 | 18 |

English volume numbers were correct whenever a single edition volume was returned (10/10; see the volume breakdown under the field-completeness note
for why "12/16" must not be read as a correct-volume rate). Volume matching is
only as good as that field: it was absent for Fullmetal Alchemist (JA), Sandman, and Ms. Marvel. **Spanish Manga has no
coverage in either provider on this corpus**, which keeps the EN/JP/ES acceptance corpus (plan §12) dependent on another
source.

### Ground-truth strength of the seven single-source fixtures

| Fixture | Category | Inventaire result |
|---|---|---|
| ja-fma1 | Manga (ja) | partial edition |
| ja-slamdunk1 | Manga (ja) | no result |
| ja-chainsawman2 | Manga (ja) | no result |
| es-naruto1 | Manga (es) | no result |
| es-aot1 | Manga (es) | no result |
| es-chainsawman1 | Manga (es) | no result |
| es-akira2 | Manga (es) | no result |

6 of the 7 are no-result and 1 is partial (an earlier review summary said "five of seven", which does not match the
corpus). The observed **0/6 Spanish** result stands, but 4 of those 6 rest on a single source and both Japanese
no-results are single-source, so those misses are weaker evidence than the 0/2 on corroborated Spanish fixtures. All seven
must be independently strengthened before they are used as authoritative 4.5G acceptance fixtures. Fixtures were not
changed to improve any result.

## 9. Response examples (no unlicensed material)

Inventaire edition (truncated, identifiers only): `{"type":"edition","claims":{"wdt:P212":["978-1-56931-901-7"],"wdt:P407":["wd:Q1860"],"wdt:P1476":["One Piece, Vol. 1: Romance Dawn"],"wdt:P629":["wd:Q19644718"],"invp:P2":["<image hash>"],"wdt:P648":["<Open Library id>"]}}`.
Wikidata edition (positive control): one row of `?item wdt:P212 "978-3-9810681-1-5"` → `wd:Q135069500`. Wikidata
stores ISBN-13 as free text, mostly hyphenated; the lab therefore queries every hyphenation split plus the digits-only
form (≈ 26 per ISBN-13) in one `VALUES` list.

## 10. Cover metadata and copyright caveats

- Inventaire: 26/28 records have an image reference (`invp:P2` hash served from inventaire.io); its API spec example shows
  an edition `image` pointing at `covers.openlibrary.org`; the wiki lists Open Library and the closed dataseed service
  as sources of edition data. **The origin and licence of each image are not exposed and were not found.**
- Wikidata: 0/4 records carry an `P18` image; images, where present, are Wikimedia Commons files under their own licences.
- CC0 applies to **data**. Publisher-owned jackets are not made free by a CC0 database, and an existing image reference is
  not proof that it belongs to this edition or may be stored. No image bytes were requested.

## 11. Service usage, attribution and the six rights questions

| Question | Inventaire | Wikidata |
|---|---|---|
| A. Data reuse | CC0 (own entities); Wikidata-backed entities CC0 | CC0 |
| B. Public API, automated client | Allowed in practice; contactable UA required; rate limits enforced but **unpublished**; contact for legitimate use cases | Allowed; UA policy and published limits |
| C. Persistent offline cache | Not addressed by the pages read; CC0 data suggests permissible, **unconfirmed** | Not restricted for CC0 data; cache is expected |
| D. Cover download/store/transform | **Unverified; rights unknown**; do not | No covers in hits; Commons licences per file |
| E. Attribution | None required for CC0; courtesy credit advisable | None required for CC0 |
| F. Free/OSS app with paid cosmetic themes | CC0 data is compatible; the API's commercial-use position **not found** | Compatible for data |

Open source status is not treated as noncommercial status for either provider. Nothing here replaces Open Library's
D1/D31 gate.

## 12. Gaps and uncertainties

Inventaire's Terms of use / Legal notice / image policy; whether a GET for an unknown `isbn:` URI causes external fetches or
seeding; its unpublished rate limits; whether cached Inventaire data may be redistributed in backups; the effect of the
closed `dataseed` source on CC0 claims; 7 single-source fixtures; fixtures chosen are mainstream titles (coverage on obscure
or self-published books is unmeasured); the lab's classification rules are conservative and its title comparison is a heuristic;
the matcher query models complete embedded metadata, which many real files lack; one run on one day.

## 13. Proposed production selection

- **Do not add either as a primary provider.** Open Library remains the only keyless edition-level provider in the plan and
  remains gated.
- **Inventaire (candidate, metadata only, secondary):** useful for English books and English/Japanese Manga identification and
  for cross-checking Open Library, because it exposes `wdt:P629` work links, series and ordinal. Because 25/28 records are
  Open Library-derived, agreement with Open Library would be weak corroboration (plan D30: corroboration ranks, never
  bypasses matching). Covers excluded.
- **Wikidata:** not a provider candidate on coverage; optionally an *identifier/identity layer* (work, author, series) in a later
  slice. Its ISBN lookup needs the hyphenation-variant technique.
- **Spanish Manga and comics:** unsolved by either; needs other sources (D5 Metron for issues, D22 LoC, others) or accepts
  review-only handling.

## 14. Conditions before any live ShelfOS integration

1. Administrator decision on adding Inventaire as a candidate (new decision row; no assumption here).
2. Read and record Inventaire's Terms of use, Legal notice and image policy; ask hello@inventaire.io about a distributed
   open-source client with optional paid themes, lazy per-user ISBN lookups, caching, and published rate limits.
3. Confirm whether `isbn:` GETs trigger server-side seeding and whether that is acceptable to Inventaire.
4. Keep covers out of scope until a cover-rights decision exists; no hotlinking.
5. Normal gates: manifest/privacy review, ADR-0026 registry entry with `termsStatus`, per-provider toggle, fake-HTTP tests,
   User-Agent with a project contact (D4), request budget ≤ 3 requests per ISBN.
6. Open Library D1/D31 remain open and unaffected.

## 15. Recommendation (original, superseded in part by §16)

**CONDITIONAL GO — Inventaire as an optional, metadata-only secondary candidate, pending conditions 1–3; NO-GO for Wikidata as
a standalone provider; NO-GO for any cover use of either.** If condition 2 cannot be satisfied, treat Inventaire as NO-GO and
continue research (other independent sources, Metron for issues).

## 16. Administrator decisions recorded after independent QA (2026-10-18)

Research approval is not production API authorization; nothing below authorizes a network permission, provider
registration, dependency or cover storage in the app.

- **Inventaire:** provisional **metadata-only candidate**; **no production authorization**. Its Terms of Use, Legal Notice,
  commercial-usage conditions and cover rights remain **unverified**.
- **Wikidata:** **not recommended as a standalone primary ISBN provider** on current evidence (4/36 exact, English books only).
- **Artwork:** no third-party image downloading or persistence is approved for either provider.
- **Seeding:** deliberate automatic creation of remote entities remains prohibited. The inspected Inventaire source suggests
  `autocreate` defaults to false, but the deployed server's behavior is unconfirmed.
- **Spanish Manga:** the **Biblioteca Nacional de España** is the next research candidate. Missing or contradictory edition
  matches remain manual / review-only.
- **Ground truth:** all seven single-source fixtures must be strengthened before authoritative 4.5G acceptance use.
- **Open Library:** D1/D31 remain unresolved; D22-D29 unchanged.

## 17. Reproducibility of the matcher results (post-QA hardening)

`report --jvm` now deletes any previous bridge output, requires a zero Gradle exit, and accepts only an output file whose first
line carries the SHA-256 of the exact input and the decision count, with every expected (fixture, probe) group present exactly
once and none unexpected. A skipped test, a missing, stale, foreign or incomplete output makes the command exit non-zero and
no matcher result is presented. Offline regeneration reuses the committed normalized snapshot
(`results/bridge_in.tsv`, `results/bridge_out.tsv`: 46 decision groups, identifiers and neutral field values only, no raw API payloads)
only if its input digest equals the digest of the input rebuilt from `records.json`; otherwise the matcher columns are marked
unverified. `results/summary.json` records the mode (`fresh-jvm-run` or `committed-snapshot`), the input digest and the group count.
The Inventaire edition chosen while parsing and the one whose work/publisher are requested now come from one shared selector
(lowest URI); several editions for one ISBN are still counted and classified ambiguous. No new request was made for any of this.
