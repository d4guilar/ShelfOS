# Phase 4.5C.P2 — Provider access, BYOK and cover-source feasibility

Status: **research and decision documentation only, 2026-10-19; pending independent QA.** No code, manifest, `INTERNET` permission, dependency,
Room, credential, network call to a provider API, cover download or scraper was added. Nothing here approves a provider: gates D1/D31 (Open
Library), D5/D19 (Metron), D25 (Hardcover) and D22 (Library of Congress) stay exactly as recorded. Earlier findings (P0 PR #51, P1 PR #52) are
not rewritten. Not legal advice.

Evidence labels: **READ** = read on an official page this pass; **SECONDHAND** = search summary, third party or earlier ShelfOS read; **UNKNOWN**
= could not be established. Pages read are listed in §14.

## 1. Executive recommendation

1. **Keyless Open Library remains the only plausible default source, and it is still unapproved.** Its public reads need no key; there is **no
   personal API key** to "bring". A SteamGridDB-style BYOK is not available for Open Library. What exists is *identification* (a `User-Agent`
   plus contact) and an optional *use-case registration form*; neither is documented to grant a key, an account quota or approval.
2. **BYOK is appropriate only where the provider officially issues individual credentials**: today Metron (per-user account or revocable token,
   officially recommended) and, conditionally, Hardcover (personal account token, beta). BYOK does not by itself grant image or caching rights.
3. **Cover-source approval is separate from metadata-source approval.** No source examined states a clear right to download, store and
   display publisher artwork offline. The permanently safe covers are the ones ShelfOS already has: embedded, first-page, generated and
   user-chosen device files.
4. **DILVE and the Spanish ISBN Agency are not viable default sources**: DILVE is a trade-chain platform with per-entity registration, annual
   fees and an explicit statement that developer access "usually is temporary"; the Agency's public interface and reuse terms could not be verified.
5. **No generic scraping fallback is authorized**, and none should be built.
6. **Next step:** with the administrator's explicit go-ahead, send the Open Library inquiry (§13.1). Until D1/D31 close, the only network-free progress
   is the local cover work that already exists. Do not start 4.5C.2.

## 2. Open Library: API keys, accounts and registration (READ, 2026-10-19)

| Question | Finding |
|---|---|
| Key required for public metadata reads? | **No.** The API page, Covers page and Books API page mention no key or authentication; examples are plain GETs. |
| Official personal API keys? | **None documented.** No key issuance, key header or key dashboard appears on the three pages. |
| Ordinary account registration → separate public quota? | **Not documented.** Nothing ties an account to a quota. |
| Application / use-case registration | A separate **form** linked from "Registering Your Application": "register your use-case or application"; stated purpose is to help Open Library understand API use. It is **not** described as issuing a key, a quota or approval. |
| Account credentials needed for reads? | No. (Secondhand, StackOne connector page: Internet Archive S3 keys exist for account *write* actions such as lists; irrelevant to ShelfOS reads.) |
| How are "authenticated" and "unauthenticated" distinguished? | There is no authenticated tier for these reads. The only distinction is **identified vs non-identified**: a `User-Agent` with application name and contact email/phone gives **3 req/s**; otherwise **1 req/s**. |

**Conclusion.** ShelfOS **cannot** adopt SteamGridDB-style BYOK for Open Library, because no personal key exists. ShelfOS must not collect Open
Library usernames or passwords for any purpose here. The workable model is an *identified application* (project-owned `User-Agent`/contact, D4),
plus the registration form and written confirmation (D31).

## 3. Direct-device traffic model

Proposal: each installation sends occasional **user-triggered** requests straight to an authorized provider, with local caching and no ShelfOS proxy.

| Aspect | Open Library's published rule (READ) | Assessment |
|---|---|---|
| Identification | `User-Agent` with app name and contact email; identified clients get 3 req/s | Compatible; use a project-owned contact (D4), never a user's |
| Intended traffic | "useful, time-sensitive requests on behalf of human users"; cache responses; priorities include open-source and mission-aligned projects and tools that help people look up books (paraphrase) | A user-triggered lookup fits; unattended library-wide backfill does not |
| Do-not list | no bulk download via APIs, no scraping, no hundreds of single-book requests (use search for batches), **no spreading requests across multiple IPs**, no backend for high-traffic commercial services | See below |
| Limits | 1–3 req/s per client; covers by ISBN/OCLC/LCCN 100 per IP per 5 min, then 403 | Per-IP, not per-account |

**Per-IP and carrier-grade NAT.** A phone behind CGNAT shares an IP with many strangers, so the ISBN-keyed cover limit (100 per 5 minutes) can be exhausted
by others and produce 403. ShelfOS must treat 403/429 as "stop and back off", never retry-storm, and prefer cover-id/OLID cover URLs (not
rate-limited per the covers page) once an edition is validated.

**Direct device use vs circumvention.** One install = one device IP is the natural result of a local-first design; it is not a ShelfOS-engineered way
around limits. What is *not* acceptable: a ShelfOS relay that rotates addresses, installs coordinated to fan out a bulk crawl, or splitting a
library-wide backfill across devices. Design rules that keep ShelfOS on the right side: lazy per-item and user-triggered lookups; the one-time
disclosure and toggle (D15); a per-device request budget; persistent backoff; no background library sweep; no proxy.

**What the pages do not answer.** Whether many installs of one open-source app, each light, count as "high-traffic commercial infrastructure" in
aggregate; whether a free app with optional paid themes is "commercial" for this purpose; and whether app registration yields any assurance. Those need
Open Library's written reply (D31). **D1/D31 remain unresolved.**

## 4. Open Library covers: ID forms, caching, rights

| Topic | Finding |
|---|---|
| Technical availability (READ) | `https://covers.openlibrary.org/b/$key/$value-$size.jpg`, key in ISBN, OCLC, LCCN, OLID or ID; sizes S/M/L; by default a blank image for a missing cover, `?default=false` gives 404 |
| Request limits (READ) | "cover access by ids other than CoverID and OLID are rate-limited": **100 requests/IP per 5 minutes**, then 403 Forbidden |
| Edition identity | A cover **ID** or **OLID** names a specific cover/edition record; an ISBN is the identity ShelfOS matches on. A cover ID taken from a **validated edition record** (exact ISBN match accepted by the production matcher) avoids repeating ISBN-keyed lookups and keeps the cover tied to that edition. *Search* results return a representative cover ID for a **work**, which can belong to another edition (general API knowledge, not re-read this pass): never auto-apply a search-level ID without edition validation. |
| Usage rules (READ) | "Please, do not crawl our cover API." It "is intended for displaying covers on public facing websites and not for bulk download"; bulk goes to archive.org. A missing rate limit on cover-id URLs is **not** permission to bulk download. |
| Copyright / artwork reuse | **Unknown.** The Covers page carries no copyright or licence statement. The licensing page says the Internet Archive "does not assert any new copyright", warns of "existing rights issues", and is silent on covers and commercial use. |
| Offline persistence | **Unknown.** The pages address *displaying* covers on public sites, not storing them in an app's private library. A downloaded-once, device-local copy for the user's own library is the intended ShelfOS behavior (plan §14 item 3) but is **not** confirmed by Open Library. |

Preserved rules: `USER` > confident online > `LOCAL` > generated (plan §17); online never silently replaces a user cover or (Comics/Manga) a CBZ/CBR
first-page cover (D7); a cover is downloaded once, never hotlinked; provider covers stay out of exports/backups.

## 5. SteamGridDB-style BYOK comparison

SteamGridDB (SECONDHAND: wrapper docs and third-party launcher notes; its official terms were not read): each user generates a personal API key
in profile preferences; apps either ask users to paste their own key or ship a shared key whose allowance is divided among all users. The model works
because the provider **issues individual keys**.

| | Personal credential | Developer-application credential | Institutional access |
|---|---|---|---|
| Example | Metron token, Hardcover token | a shared key shipped in an app (Google Books style) | DILVE entity account |
| Fits ShelfOS | yes, if officially supported | **no**: cannot ship in an open-source APK and would misrepresent separate users as one client | no: not issued to ordinary readers |
| Open Library | **does not exist** | n/a | n/a |

Rules for any BYOK provider: the credential is the user's; stored locally; never logged, exported or backed up; only sent to that provider; removable;
and **BYOK does not grant image, caching or redistribution rights** (those come from the provider's terms, per provider).

## 6. Metron and Hardcover eligibility

| | Metron (READ 2026-10-19: official commercial-use statement) | Hardcover (SECONDHAND; official docs returned 403 to my reader) |
|---|---|---|
| Media | Comics, issue-level | Books |
| Credential | **Per-user account; revocable tokens recommended**; "Don't ship your app with a single built-in account that all of your users share" | Personal account token from account settings; tokens "should be kept private"; may be reset without notice (beta) |
| Limits | 20 req/min and 5,000/day **per account**; "don't create extra accounts to get around the limits" | 429 documented; numeric limit UNKNOWN (60/min is third-party, unverified) |
| Caching | store fetched data rather than re-request | UNKNOWN |
| Covers | **not licensed**: "Metron doesn't hold a license for cover images"; fair-use reasoning "doesn't automatically extend to your use"; using Metron only to identify comics "avoids the question entirely" | UNKNOWN |
| Data licence | CC BY-SA 4.0; commercial use allowed with attribution; ShareAlike applies to Metron *data*, not the rest of the software | "cannot use the API to access and use someone else's data" (as summarized); licensing UNKNOWN |
| Eligibility | **Candidate BYOK for metadata only**, covers excluded; D5/D19 (current `metron.cloud` service terms, ShareAlike effect on caches/exports/backups, Plus themes) **still open** | **Optional BYOK candidate**, beta; official terms must be read; D25 **stays conditional** |

Other personal-token providers: none was found that is both officially documented and directly useful. Comic Vine stays rejected ("strictly non-commercial");
ISBNdb is a paid developer key (not a reader credential); Google Books stays rejected (D2). Nothing else was researched this pass; no provider is
recommended on a guess.

**Proposed UX (design only; not authorized):** Settings → Online metadata → *Provider* → **Connect**: provider description and media; official key-generation
guide link; plain statement of what is sent (ISBN or title/creator query, device IP, app User-Agent, the token); secure local entry; *Test connection*;
rate-limit status; *Disconnect and forget*; provider attribution. No credential table, settings screen or Keystore code is added now; the existing
`CredentialRequirement` slot in the provider descriptor already anticipates it.

## 7. Spain's ISBN Agency

| Question | Finding |
|---|---|
| Official site | `agenciaisbn.es` returned an **empty page** to my reader; its content, including the "new integrated platform announced in October 2026", could **not be verified** (a web search found no confirmation). |
| Legal role (SECONDHAND, Real Decreto 2063/2008) | The Agency maintains the ISBN databases and provides "a system of consultation to enable dissemination and commercialisation of books published in Spain"; records appear in the Ministry's *Base de datos de libros editados en España*. A 2016 BNE–FGEE agreement eases ISBN-data download for library cataloguing. |
| Management | A partisan petition says management was delegated to the FGEE; undated and unofficial. |
| Free API credentials for consumers | **None found.** |
| Developer testing, production, caching, covers, open-data licence | **UNKNOWN**; no public API or open-data licence was found. |

Public catalogue scraping is not done and not proposed.

## 8. DILVE (READ, 2026-10-19)

| Question | Finding |
|---|---|
| What it is | The book trade's metadata distribution platform (FGEE): ~2.0 M books, 1,491 publishers; publishers upload 300+ fields including images; distributors, bookshops/online stores, software developers and libraries receive them (ONIX, SINLI, CSV, HTTP-GET calls, FTP). |
| Consumer / reader access | **None described.** A login and registration model for supply-chain entities. |
| Software developers | May request registration; the company must appoint a "Responsable DILVE" (liaison with the FGEE); conditions are emailed and must be accepted; **no prices published** for developers (fgee@dilve.es). The FAQ says developer access "suele ser temporal": in production the **user entities must register with their own users**. Free assistance for building compatibility modules. |
| Costs (tariff page) | Libraries: free. Bookshops/online stores: by annual revenue, 100 € to 1,000 €/yr (0 € for some collaborators). "Other users": 200 € non-profit, 400 € for-profit per year. **Commercial data extraction: 3,600 €/yr.** Publishers pay per active title. No API fee line. |
| Low-volume individual lookups | Not distinguished anywhere. |
| Caching / retention | Not addressed for third parties; the FAQ tells entities to download cover files locally for their own sites. |
| Cover references and image rights | Publisher-uploaded covers or publisher URLs, downloadable by registered users via extraction or HTTP. **Reuse conditions for third parties are not stated.** |
| Free/OSS app with paid themes | No special condition found. |

**Conclusion:** a production ShelfOS integration would require either every reader to hold a DILVE registration (not a consumer product) or a single
ShelfOS institutional account (shared-credential model, plus fees and unstated redistribution terms). **Not a default source; CONDITIONAL research
candidate only, pending a written reply (§13.2).** Developer access is not a production licence. No payment or subscription was made.

## 9. Artwork and scraper fallback policy

Key distinction: **user consent to request an image is not permission from the source to retrieve, store or reuse it.** A user choosing to fetch a
file does not give ShelfOS rights the source has not granted.

| Fallback | Works offline? | Source permission needed? | Status |
|---|---|---|---|
| Embedded cover (EPUB), CBZ/CBR first page or declared front cover, PDF first page | yes | none (user's own file) | exists (4.5A) |
| Generated cover | yes | none | exists |
| *Choose from device* (USER slot) | yes | none (user's own file) | exists (4.5F.0) |
| Online candidates from an approved provider | no | yes, per provider, for cover bytes | gated by D1/D31 and a separate **cover-rights** decision |
| User-pasted HTTPS image URL | no | the host's terms; ShelfOS cannot verify them | **not recommended in 4.5**; if ever added, a user action like a browser download, no discovery or crawling, with legal review |
| Clearly licensed public-domain/CC artwork (for example generated covers of public-domain books, Commons) | no | licence of each file | RESEARCH only; observed in P0 that Gutendex exposes a Gutenberg-generated `image/jpeg`, rights not verified |
| BYOK cover service | no | per provider | none identified |
| Scrapers for bookstores, publishers, social networks, or services that forbid automation | — | prohibited | **not authorized, not to be built** |

## 10. Provider decision matrix

Status vocabulary here includes UNKNOWN (the code's `TermsStatus` has only APPROVED/CONDITIONAL/REJECTED; an UNKNOWN provider is simply not registered).
"Role" = what it could become *if* its gate closes. Nothing is APPROVED.

| Provider | Media | Edition-level | Metadata | Covers | Credential | Public limits | Android-client eligibility | Caching | Cover-art rights | Cost | Attribution | Status | Role if gate closes | Next step |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **Open Library** | Books (some Manga/TPB) | yes (edition records, ISBN) | yes | yes (id/OLID/ISBN) | none; identified UA | 1–3 req/s; covers by ISBN 100/IP/5 min | stated **unknown**; open-source apps are a stated priority | "cache whenever possible" (API) | **unknown** | free | courtesy link | **CONDITIONAL (D1/D31)** | default keyless metadata (and cover source only after a cover-rights answer) | send the §13.1 inquiry |
| Inventaire | Books | yes | yes | image refs (rights unknown) | none; contactable UA | enforced, unpublished | unknown (terms unread) | unknown (CC0 data) | **unknown** | free | none (CC0) | CONDITIONAL (provisional, P0) | metadata-only corroboration (not independent of Open Library) | read terms; email hello@inventaire.io |
| Wikidata | Books | partial (4/36 in P0) | yes | none in P0 | none | published | data CC0; UA required | allowed for CC0 data | n/a | free | none | NOT a standalone provider | identity/authority layer | none now |
| Library of Congress | Books (metadata) | ISBN path unresolved | yes | none | none | 20 req/min, 1 h block | unknown | unknown | n/a | free | unknown | CONDITIONAL (D22) | metadata-only | verify ISBN path and terms |
| BNE | Books | 0/6 Spanish Manga (P1) | yes (MARC) | none | none | unpublished | unknown | unknown (CC0 claimed, unverified) | n/a | free | unknown | not recommended for Spanish Manga | general books/authority (untested) | terms question, info.catalogo@bne.es |
| Hardcover | Books | yes | yes | yes (rights unknown) | personal token | 429; number unknown | per-user token only | unknown | unknown | free | unknown | CONDITIONAL, BYOK only (D25) | optional BYOK | read official API terms |
| Metron | Comics (issue) | yes | yes | not licensed | per-user token | 20/min, 5,000/day per account | per-user tokens only | encouraged | **none held by Metron** | free | CC BY-SA credit | CONDITIONAL (D5/D19) | optional BYOK, metadata only | close D19 and read service terms |
| Comic Vine | Comics | yes | yes | yes | per-user key | 200/h/resource | "strictly non-commercial" | no editing/reproduction | no | free | link back | REJECTED for default | — | none |
| ISBNdb | Books | yes (ISBN) | yes | yes | paid key | plan-based | key cannot ship | unknown | unknown | paid | unknown | UNKNOWN (BYOK, later) | optional paid BYOK | none now |
| Google Books | Books | yes | yes | thumbnails | key | per key | credentials barred in OSS; no-fee clause | no permanent copies | restricted | free | required branding | REJECTED (D2) | — | reopen only with written permission |
| OverDrive | library catalogs | yes | yes | yes | vendor key + secret | unread | secret cannot ship | unread | branding required | n/a | Libby/Sora | REJECTED as default (D26) | — | none |
| Gutendex | public-domain texts | partial | yes | `image/jpeg` observed | none | undocumented | public instance asks self-hosting | unknown | unknown | free | unknown | REJECTED as default (D24) | specialist research | none |
| bookcover-api (Goodreads) | Books | n/a | no | yes | none | none | n/a | n/a | no stated image rights | free | n/a | REJECTED (D27) | — | none |
| DILVE | Spanish trade metadata | yes | yes | publisher covers | entity registration | n/a | entity-based, not for readers | unstated | publisher-owned, reuse **unknown** | 100–3,600 €/yr by profile, developers unpublished | unknown | UNKNOWN / not viable as default | research only | §13.2 inquiry |
| Spanish ISBN Agency | Spanish books | yes | yes | no | none known | unknown | unknown | unknown | n/a | unknown | unknown | UNKNOWN | research only | verify its public interface and terms |
| Local sources (embedded, first page, generated, device) | all | the user's own file | embedded fields | yes | none | none | n/a | n/a | the user's file | free | none | **exists, APPROVED by design (no provider)** | permanent offline fallback | none |

## 11. Current authorization blockers

1. **D1/D31:** Open Library's written position on a distributed, user-triggered client, application registration and the aggregate-traffic question.
2. **Cover rights:** no examined source grants rights to download, store and display publisher artwork; needs a per-provider decision separate from metadata.
3. **D5/D19:** Metron service terms and the effect of ShareAlike on caches, exports and backups; Metron has no cover licence.
4. **D25:** Hardcover's official API terms (unreadable to my reader).
5. **Inventaire:** unread terms of use and image policy.
6. **BNE/DILVE/Agency:** unverified reuse terms and no consumer access path.

## 12. Concrete next development recommendation

1. Obtain the administrator's go-ahead and send the Open Library inquiry (§13.1). Record the reply under D1/D31, whichever way it goes.
2. Treat the cover-rights decision as its own gate: until it closes, online providers contribute **metadata only** and candidate cover *references* in the picker UI
   are not downloaded.
3. Design (not build) the BYOK credential store and Connect flow as an ADR once Metron or Hardcover is closer to approval; the descriptor already has `CredentialRequirement`.
4. Keep investing in the offline fallbacks (all permanent and rights-free).
5. Do not pursue DILVE or the Agency further without a written answer to §13.2.

## 13. Questions requiring written provider clarification (DRAFTS ONLY, not sent)

### 13.1 Open Library (to openlibrary@archive.org, and the use-case registration form)

> Subject: ShelfOS (open-source Android reading app) — guidance on direct-device API use and cover caching
>
> Hello Open Library team,
>
> ShelfOS (https://github.com/d4guilar/ShelfOS) is a free, open-source, local-first reading app for Android. The core app is free; optional paid cosmetic
> themes and donations support development. We would like to offer optional book metadata and cover suggestions using the Open Library APIs, and we want to do
> it within your usage guidelines.
>
> Proposed behavior: each installation sends only occasional requests that the reader triggers (for example opening "Find cover" on one book), identified by a
> project User-Agent with a contact address; ISBN lookups first, then covers by cover ID or OLID; no bulk or background library sweeps; responses cached on the
> device; backoff and stop on 403/429; no ShelfOS proxy or request relaying.
>
> Questions:
> 1. Is this direct-from-device model acceptable for an open-source app with optional paid themes, or do you consider it commercial use needing a different arrangement?
> 2. Does registering the application through the form give any assurance, quota or key, or is it informational only? Is any API key or account mechanism planned?
> 3. Readers behind shared (carrier-grade NAT) addresses may hit the per-IP limits for ISBN-keyed cover requests. Is using cover-ID/OLID URLs after validating an edition the recommended pattern?
> 4. May an app store a downloaded cover image locally, once, for display in the user's own library offline? Are there licence or rights conditions on the cover images (including publisher artwork) that we must pass on?
> 5. What attribution do you want, and where?
>
> Thank you; we will follow whatever guidance you give.

### 13.2 DILVE / Spanish ISBN Agency (to fgee@dilve.es and the Agency)

> Asunto: ShelfOS (aplicación de lectura de código abierto) — elegibilidad de acceso a metadatos y portadas / Eligibility for metadata and cover access
>
> ShelfOS (https://github.com/d4guilar/ShelfOS) es una aplicación Android gratuita, de código abierto y local-first para leer libros, cómics y manga. Solo cobra por
> temas visuales opcionales. Queremos saber si existe una vía autorizada para consultas individuales por ISBN iniciadas por el lector.
>
> 1. ¿Puede una aplicación de lectura personal (sin editorial, librería ni biblioteca) obtener acceso a DILVE o a la base de datos de la Agencia del ISBN? ¿Con qué perfil y condiciones?
> 2. ¿El acceso de desarrollador/pruebas es solo temporal? ¿Qué se necesita para un uso en producción? ¿Debe cada lector tener credenciales propias, o puede una entidad (el proyecto) usar una cuenta?
> 3. ¿Se aplican tarifas (incluida la de extracción comercial) a consultas de bajo volumen iniciadas por usuarios? ¿Hay condiciones para proyectos gratuitos y de código abierto?
> 4. ¿Se puede conservar en caché local la información devuelta y durante cuánto tiempo?
> 5. ¿Las URLs o archivos de portada pueden descargarse, guardarse en el dispositivo y mostrarse? ¿Qué derechos conceden las editoriales?
> 6. ¿Qué atribución se exige?
>
> English: Is there an authorized route for reader-triggered, individual ISBN lookups from a free open-source reading app; what profile, free developer access vs production
> terms, individual vs entity credentials, fees, local caching and retention, and rights to download/store cover images apply; and what attribution is required?

## 14. Sources, access dates, uncertainties

Read 2026-10-19: [Open Library API](https://openlibrary.org/developers/api), [Covers](https://openlibrary.org/dev/docs/api/covers),
[Books API](https://openlibrary.org/dev/docs/api/books), [Licensing](https://openlibrary.org/developers/licensing),
[Metron commercial-use statement](https://metron-project.github.io/blog/commercial-api-use) (re-read), [DILVE developers](https://web.dilve.es/dilve/dilve-para-desarrolladores-y-empresas-de-software/),
[DILVE tariffs](https://web.dilve.es/tarifas/), [DILVE FAQ](https://web.dilve.es/asistencia-2/preguntas-frecuentes/), [DILVE home](https://web.dilve.es/).
Could not read: agenciaisbn.es (empty response), the Metron API docs (bot-protection page), Hardcover docs and SteamGridDB terms (search summaries only), Open Library Terms of
Service (not fetched). Secondhand: StackOne note on Internet Archive S3 keys, Real Decreto 2063/2008 summaries, petition about FGEE management, SteamGridDB key flow.

Unresolved uncertainties: Open Library's aggregate-traffic and commercial interpretation; cover-image rights and offline-storage permission for every source; the October 2026
Spanish ISBN platform; Hardcover and Metron service terms; all DILVE third-party reuse terms. The tariffs and conditions above may change.
