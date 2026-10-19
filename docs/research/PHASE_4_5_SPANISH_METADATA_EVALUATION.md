# Phase 4.5C.P1 — Biblioteca Nacional de España (BNE) for Spanish Manga

Status: **research spike, read-only, executed 2026-10-19; pending independent QA.** Not a production integration. It authorizes no
network permission, provider registration, dependency, cover download or cover storage. Phase 4.5C.P0 (PR #51, merged) and its
Inventaire/Wikidata results are unchanged; Open Library D1/D31 stay unresolved; Inventaire stays a provisional metadata-only candidate.

## A. Executive summary

- The documented **BNE SRU 1.2** service works: the Explain response declares the `alma.isbn` index, the `=` relation and the MARCXML schema.
- On the six Spanish Manga fixtures from P0 it returned **0 records for 6/6 ISBNs** (6/6 HTTP 200 with a valid empty result; 0/6 exact
  editions). No request was blocked or failed.
- Two bounded controls show the ISBN query path itself works (a BNE record found by its own ISBN-13), so these are genuine "no record for
  this ISBN" answers, **not** a query defect, and not evidence that the ISBNs are invalid.
- A holdings diagnostic shows *why*: BNE does catalogue these works, but at **series/multipart level without volume ISBNs** (for example,
  Norma's *Chainsaw Man* 2020– is one record with no ISBN), or under other editions' ISBNs. An ISBN lookup therefore cannot identify a
  Spanish Manga *volume* in BNE.
- **Recommendation: NO-GO for BNE as a Spanish Manga edition-level metadata source** on this evidence (0/6, with a structural reason). It may still be useful for
  other, mostly older or non-serial Spanish books, which this experiment did not measure. Next source: see §O.

## B. Official interfaces verified

| Interface | What was verified |
|---|---|
| SRU 1.2, `https://catalogo.bne.es/view/sru/34BNE_INST` | **Live**: Explain returned HTTP 200 (186,457 bytes, 2.7 s), `version 1.2`, 399 indexes, schemas `marcxml, dc, mods, dcx, unimarcxml, kormarcxml, cnmarcxml, isohold`, and the index `ISBN` mapped to `alma.isbn` supporting relations `=`, `all`. `searchRetrieve` with `recordSchema=marcxml&maximumRecords=5` returned well-formed SRU responses (`numberOfRecords` and `records`). Server is Ex Libris Alma. |
| Documentation | The BNE SRU description ("Download with SRU", bne.es) and the linked-data page were **not readable** here: bne.es answers programmatic requests with 403 (a Cloudflare challenge page; one honest-User-Agent request returned 403, another a 301). I did not try to get around that. Documentation facts therefore come from a search-engine summary of the BNE page (base URL, SRU 1.2, `explain`/`searchRetrieve`, CQL, `alma.*` indexes, marcxml/dc/mods, contact info.catalogo@bne.es) and are confirmed where the live Explain overlaps. |
| Linked data, `https://datos.bne.es/sparql` | **Not contacted.** SRU answered the benchmark, so no second adapter was built. Its currency relative to the live catalogue (linked-data exports can lag) and its ontology applicability were not assessed. |

## C. Data licensing and service use (separately assessed)

| Question | Finding |
|---|---|
| Bibliographic data reuse | BNE describes its catalogue data as CC0 (search summaries of the BNE linked-data pages; a 2012 review noted unclear reuse terms for images/content). **The current BNE legal/reuse pages could not be read**, so CC0 is reported, not verified. |
| Public search infrastructure | No published request limit was found. The server is an Alma SRU; the research kept to ≥ 2 s between requests and 15 requests in total. |
| Distributed Android client | Not addressed by anything read. Production use needs written clarification: info.catalogo@bne.es. |
| Offline caching | Not addressed. CC0 would permit it if confirmed. |
| Artwork and digital images | Not covered by metadata CC0 and **not touched**: a MARC record carries no cover, none was requested. |
| Attribution | None required under CC0; courtesy credit advisable. Unverified. |

## D. Corpus and verification confidence

Exactly the six P0 Spanish fixtures, unchanged (`fixtures/isbn_corpus.json`): One Piece 1 and Naruto 1 (Planeta Cómic), Ataque a los
titanes 1, Chainsaw Man 1 and Akira 2 (Norma), Maximum Berserk 1 (Panini, an **omnibus**). New evidence is recorded separately in
`fixtures/es_ground_truth_p1.json` (P0 corpus untouched). Ground truth was **never** derived from BNE.

| Fixture | P0 | P1 result |
|---|---|---|
| es-onepiece1 | multi-source | unchanged |
| es-maxberserk1 | multi-source | unchanged (omnibus: edition number 1 is not manga volume 1) |
| es-naruto1 | single | **strengthened to 2 retailer sources** (+ Polifemo EAN, 12×17 cm, 192 pp) — no publisher/national-library confirmation |
| es-chainsawman1 | single | **strengthened to 2 sources** (+ Farmatodo retailer, 192 pp, 2020, "castellano") — no publisher/national-library confirmation |
| es-aot1 | single | **still single-source** (no second ISBN-level source found) |
| es-akira2 | single | **still single-source** |

So 2 of the 4 weak Spanish fixtures gained a second source, both retailers/aggregators that may share distributor feeds; 2 remain weak. BNE
supplied no corroboration for any of them. The three weak Japanese fixtures from P0 are untouched and remain pending for Phase 4.5G.

## E. P0 Spanish baseline (unchanged)

Inventaire: 0/6 records. Wikidata: 0/6. (Both reported again here only for comparison; not recomputed.)

## F. SRU pilot (Stage B)

| Request | Result |
|---|---|
| Explain | HTTP 200, valid, `alma.isbn` supported |
| `alma.isbn="9788468471525" or alma.isbn="8468471526"` (One Piece 1) | HTTP 200, `numberOfRecords=0`, 1.41 s |
| same for Maximum Berserk 1 (`9788411015035`) | HTTP 200, `numberOfRecords=0`, 0.94 s |

Each query asks for the ISBN-13 and, where one exists, the ISBN-10 in a single request. Because two empty answers cannot separate "not held"
from "wrong query form", I ran two bounded controls: a title search (`alma.title="One Piece"`, 3 records) that returned a Planeta record
(*Chopperman*, ISBN `9788416051755`), then an ISBN search for that ISBN-13, which returned exactly that 1 record. The query form is valid.

## G. Six-ISBN results (Stage C) — denominators are 6

| | Reached (HTTP ok) | Records | Exact edition | Partial / ambiguous / wrong / conflicting | No result | Failed |
|---|---|---|---|---|---|---|
| BNE (SRU, `alma.isbn`) | 6/6 | 0/6 | 0/6 | 0 | 6 | 0 |

ShelfOS auto-accept 0/6, with *no candidates to score*. Median latency 0.76 s, maximum 1.41 s (one request per ISBN); 6 requests for
6 ISBNs; the four non-pilot ISBNs were queried once each and the pilot results were reused.

## H. Edition identity and Manga-volume accuracy

Not measurable: there was no record to inspect. The parser and classifier were exercised offline instead (§J): a series enumeration or
245$n part number becomes a volume only when it is purely a number or range; a missing volume stays unknown; a bare "1" never proves a
single volume or an omnibus (a collection fixture is exact only when the record itself shows collection evidence); several records for one
ISBN are ambiguous; a record without the queried ISBN is wrong/partial; Japanese or other-language 041 codes conflict with `es`.

## I. Ground-truth strengthening

See §D. Result: modest (2 of 4 improved to two non-independent retail sources).

## J. Requests, latency, errors, tests

| Request group | Count | HTTP | Seconds (min–max) |
|---|---|---|---|
| Explain | 1 | 200 | 2.67 |
| Benchmark `searchRetrieve` | 6 | 200 | 0.75–1.41 |
| Controls | 2 | 200 | 0.92–1.75 |
| Holdings diagnostic (run twice, 3 titles each, 3 records max) | 6 | 200 | 1.22–3.44 |
| **Total to catalogo.bne.es** | **15** | all 200 | |

No 403/429, no retries, no timeouts. Separately, two documentation requests to www.bne.es returned 403 and 301 (see §B). No request went to
datos.bne.es. Offline tests: 25 new (URL/CQL construction and escaping, Explain parsing, hostile XML, MARC parsing with diacritics and
unknown fields, volume rules, cancelled ISBNs, multiple records, diagnostics, omnibus and wrong-volume classification, transport 403/429,
report fail-closed behaviour); full provider-lab suite **84/84**.

## K. Matcher verification

**The production Kotlin matcher was not executed**, because it has nothing to score: all six answers were empty, and a matcher decision is
never fabricated. The P1 report path records mode `no-ok-records`; with OK records it reuses the P0 fail-closed bridge (SHA-256 header,
complete groups, strict decision fields). That path was tested offline only (an OK record without a verified snapshot fails closed).

## L. Freshness and coverage limitations

Six fixtures; mainstream 2013–2023 Spanish Manga; one catalogue. The diagnostic shows BNE holds *Chainsaw Man* (Norma, 2020, `D.L. 2020-`) as
a series record without volume ISBNs, *Ataque a los Titanes* under other Norma ISBNs (2014, 2018, 2019), and *Naruto* only as film/spin-off
records in its first hits. This is consistent with legal-deposit cataloguing at set level, but whether a volume was deposited, not yet
catalogued, or catalogued under another record cannot be told from these queries. A BNE miss says nothing about the validity of a publisher's ISBN
(the Spanish ISBN agency's registry is a different database). Linked-data freshness was not assessed.

## M. Recommendation

**NO-GO for BNE as an edition-level Spanish Manga metadata source** (0/6; structural cause). **Not** a NO-GO on BNE generally: it is an official,
CC0-labelled, structured catalogue that might serve older books, authority data or work-level (series) identification, none of which was tested.

## N. Open questions

1. BNE's current data-reuse and service-use terms (the bne.es pages are unreadable programmatically): CC0 scope, any request limits, distributed-app use, caching. Ask info.catalogo@bne.es.
2. Is volume-level cataloguing of manga planned or available through another BNE record type or the linked-data service?
3. Does the Spanish ISBN agency expose an official, citable read-only API for publisher-registered ISBNs?

## O. Next source

The Spanish ISBN agency's registry (the Ministry of Culture's ISBN database), if a permitted read-only interface exists, because it holds
publisher-registered volume ISBNs; and publisher catalogues (Planeta, Norma, Panini) for edition ground truth. Both need their own terms review;
none is approved. P0's Inventaire result for Spanish Manga remains 0/6.
