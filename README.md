# cf-maven-repo

Publish and manage a Maven repository on S3-compatible object storage — Cloudflare R2, MinIO, S3.

A Maven repository is not a service. It is a naming convention over HTTP GETs: to resolve
`org.example:lib_3:1.0` a resolver asks for
`<repo>/org/example/lib_3/1.0/lib_3-1.0.pom`. Any object store that serves objects over public
HTTPS is therefore a read-only Maven repository, and the object key *is* the repository path.

What that arrangement does *not* give you is everything a registry does on your behalf — checksum
sidecars, `maven-metadata.xml`, immutability, cache correctness. That is what this does.

```bash
sbt cli/assembly
java -jar target/out/jvm/*/cf-maven-repo-cli/cf-maven-repo.jar \
  publish --staging ./staged --bucket my-maven
```

## How it fits together

Build tools already know how to write a Maven-layout directory:

```bash
scala-cli --power publish -R ./staged .             # scala-cli
sbt publish   # with publishTo := Some(MavenCache("staged", file("...")))
```

Neither needs object-store credentials — they write a directory, and this uploads it. That split
is deliberate: a release job's credentials should reach one bucket and nothing else, and it keeps
the same code path working against a Nexus or an Artifactory.

## Modules

| module | depends on | for |
|---|---|---|
| `core` | AWS SDK v2, maven-artifact | the whole publishing model. No HTTP or JSON stack. |
| `cloudflare` | core, sttp4, jsoniter | edge cache invalidation after a publish |
| `cli` | both, case-app | the command-line front end |

`core` deliberately pulls in nothing beyond the S3 client and Maven's version comparator, so
publishing to a plain bucket, a Nexus or an Artifactory costs you no transitive weight for a CDN
you are not using.

## What it guarantees

**A published version is never rewritten.** Every artifact PUT is conditional
(`If-None-Match: *`), so two concurrent runs cannot both believe they won, and a pre-flight check
turns the common case into a readable error rather than a race report. The only keys a publish may
overwrite are `maven-metadata.xml` and its checksums.

Because those keys are immutable, nothing wrong can ever be corrected in place, so a staged
checksum is verified against the artifact beside it before either is uploaded, a POM is refused
unless it states the coordinates it is being filed under, and a `-SNAPSHOT` version is refused
unless you ask for it explicitly. "States" means readable from the POM itself, taking `<parent>`
into account: a coordinate left to a property is refused rather than assumed, since a POM whose
own coordinates do not resolve from its own text is one no registry accepts either.

**A version becomes visible only once it is complete.** Within a version the POM is written last,
after every other file and after every checksum, and it is the POM that both this publisher and
any resolver treat as "this version exists". An upload interrupted halfway leaves a version that
no metadata lists and no resolver will accept.

**`maven-metadata.xml` is rebuilt from what the store holds**, never from what a run produced —
otherwise two parallel or partial runs each write a version list missing the other's work. It is
written *last*, after every artifact: until metadata lists a version, a resolver doing range
resolution will not try to fetch it, so an interrupted round leaves unreferenced files rather than
a repository advertising artifacts that are not there. Only versions whose POM has landed are
listed, and the rewrite is itself conditional on the version list not having changed since it was
read, so a publisher that loses the race re-reads and retries rather than overwriting.

Note neither sbt nor scala-cli emits this file. It is a repository-level aggregation across
versions, and a build only knows the version it just made; on a real registry the *server*
generates it. That is precisely the service a static object store gives up.

**Two cache classes.** Artifacts are immutable and cached forever; metadata is mutable and given a
short TTL. Getting this backwards is the one way a static repository actively misbehaves — stale
metadata means version ranges resolve against yesterday's list for as long as the TTL says.

**Maven's own version comparator.** Ordering `<latest>` and `<release>` correctly is subtler than
it looks, so `org.apache.maven:maven-artifact` does it rather than a hand-rolled approximation.
Known qualifiers (`alpha`, `beta`, `milestone`, `rc`, `snapshot`) sort *below* the bare release;
`sp` and every *unknown* qualifier sort *above* it. So `1.0-rc1 < 1.0 < 1.0-foo`.

## Concurrency

Uploads run at a bounded parallelism, default 32, as one global limit across the run rather than
per artifact. Measured against Cloudflare R2 with 4KB objects:

| concurrency | 8 | 16 | 32 | 64 | 128 |
|---|---|---|---|---|---|
| obj/s | 37 | 70 | 95 | **102** | 32 |

Throughput collapses past ~64 because the store throttles on total in-flight requests. With 1MB
objects it is bandwidth-bound instead, and 32 beats 64. The HTTP client is never the bottleneck —
netty, apache and url-connection all measured within noise of each other — which is why `core`
uses the smallest of them.

## Commands

```
publish --staging <dir> --bucket <b> [--prefix releases] [--endpoint <url>] [--region <r>]
        [--path-style] [--dry-run] [--skip-existing] [--allow-empty] [--allow-snapshots]
        [--trust-staged-checksums] [--trust-pom-coordinates] [--ledger <path>]
        [--parallelism 32] [--public-url <url> --cf-zone-id <id>]

republish-metadata --bucket <b> --group <groupId>
```

`--endpoint` can be omitted when `AWS_ENDPOINT_URL_S3` is set; credentials come from the standard
AWS chain. `--region` is only needed for real AWS S3, and only when the environment does not
already supply one — behind a custom endpoint it defaults to `auto`, which is what S3-compatible
stores expect. `--path-style` addresses the bucket as a path rather than a subdomain, which MinIO
and most self-hosted stores need. `--public-url` and `--cf-zone-id` go together: without a zone
there is nothing to purge. `--ledger` records uploaded keys so an interrupted round resumes without re-checking
every object. `republish-metadata` is the repair path when the staging tree is gone or an artifact
the current round did not touch has bad metadata — it is *not* needed after an interrupted round,
since `publish --skip-existing` rebuilds metadata for skipped versions.

## The zone's cache rules

`infra/` can add two cache rules for the repository hostname, and it is **off by default**
(`manageCacheRules`). The reason is ownership: Cloudflare allows exactly one entry point ruleset
per phase per zone, and writing it replaces its entire rule list — "append a rule" does not exist
as an operation. A program that manages that ruleset manages *every* cache rule on the zone,
including rules it never wrote.

**What you give up by leaving it off.** Less than it looks. The publisher already sets
`Cache-Control` on every object as it uploads — `immutable, max-age=31536000` on artifacts,
`max-age=60, must-revalidate` on metadata — and Cloudflare honours origin headers, so both cache
classes work without any rule. What the rules uniquely add is **negative-caching suppression**:
Cloudflare caches a 404 for about three minutes by default, so a resolver that asks for a version
just before it is published keeps seeing "missing" after it exists. If nothing probes ahead of
publication, that never bites.

**With it on**, the program reads the phase first and preserves everything already there,
appending its own two rules last — last match wins for cache rules, so its rules decide the
outcome for the repository hostname and every other rule still decides its own. Preservation is
verified rather than assumed: a rule using anything the program cannot reproduce exactly stops the
deployment, naming the rule and the setting, instead of being silently rewritten. Cache keys are
in that category on purpose.

**Adopting a zone that already has cache rules** takes one manual step, because Cloudflare refuses
a second entry point and the provider would otherwise fail with error 20217:

```bash
# the id of the phase's existing entry point
curl -s -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  "https://api.cloudflare.com/client/v4/zones/<zone>/rulesets/phases/http_request_cache_settings/entrypoint" \
  | jq -r .result.id

pulumi -C infra import cloudflare:index/ruleset:Ruleset maven-cache-rules "zones/<zone>/<ruleset-id>"
```

Note `zones/` plural — the provider's own error message documents the format, and the Terraform v4
docs give a different one that does not work here. After the import, ordinary `pulumi up` merges.

Two consequences worth knowing. The adopted ruleset keeps **its** name and description, not this
program's: the provider treats a name change as a new identity and replaces the resource, and a
replacement is a second entry point, which is refused. And when the phase holds rules this program
did not write, the resource is marked retain-on-delete — `pulumi destroy`, or turning the flag back
off, drops it from Pulumi's state and leaves the zone alone, so its two rules stay behind for you
to remove by hand. Leaving two dead rules on a hostname that no longer resolves is the cheaper
mistake; deleting someone else's cache policy is not. That protection is recorded in stack state
when the resource is first applied, so it covers every run after the one that adopted the zone.

## Credentials

Two tokens. Reading the repository needs neither — it is public over HTTPS.

**Deploy token** — runs the Pulumi program in `infra/`. Read as `CLOUDFLARE_API_TOKEN`. The last
two rows are needed only when `createCiToken` is on, and are account-scoped rather than the
user-scoped *API Tokens* groups: the credential this mints is account-owned, so it is read and
written through `/accounts/<id>/tokens`.

| Permission | Scope | For |
|---|---|---|
| Workers R2 Storage: Edit | account | the bucket and its custom domain |
| Zone: Read | zone | resolving the zone |
| DNS: Edit | zone | the custom domain's DNS record |
| Cache Settings: Edit | zone | only with `manageCacheRules` — reading and writing the zone's cache rules |
| Account API Tokens: Read | account | only with `createCiToken` — resolving the CI token's permission groups by name |
| Account API Tokens: Edit | account | only with `createCiToken` — minting it |

**CI token** — what a release job holds. `infra/` can mint it, or make it by hand.

| Permission | Scope | For |
|---|---|---|
| Workers R2 Storage Bucket Item Write | one bucket | uploading objects |
| Cache Purge | one zone | invalidating metadata after a publish |

Take the bucket-scoped **Bucket Item Write** group, not the account-wide *Workers R2 Storage
Write* — that one can create and delete buckets across the whole account.

The CI token is consumed two ways, because R2 derives S3 credentials from a Cloudflare token:

```bash
AWS_ACCESS_KEY_ID=<token id>              # uploads, SigV4
AWS_SECRET_ACCESS_KEY=<sha256 of value>
CLOUDFLARE_API_TOKEN=<token value>        # purging, bearer
```

It deliberately holds no infrastructure permission: a leaked release credential can write objects
into one bucket and bust one cache, and nothing else.

## Testing

```bash
sbt test          # unit, publisher recovery, and the S3 contract against MinIO
sbt cli/assembly  # the runnable jar
```

The S3 contract suite starts its own MinIO through Testcontainers — [Silo](https://github.com/pgsty/silo),
the maintained fork, since MinIO no longer publishes images — and checks the parts no
in-memory stand-in can: conditional writes, the error mapping around them, and paginated listing.
It skips itself where there is no Docker, or with `CF_MAVEN_NO_DOCKER` set.

```bash
e2e/local-flow.sh   # publish and resolve, end to end, against a disposable MinIO
```

Because a Maven repository is a naming convention over HTTP GETs, almost all of it can be checked
without an account: this starts a MinIO, makes the bucket anonymously readable, publishes two
libraries built by two different build tools, and resolves them back with scala-cli, coursier, sbt,
gradle and mill — version ranges included. Only edge caching and purging need a real CDN.

To run the harness against a real repository, say so in the environment:

```bash
BUCKET=my-maven REPO_BASE=https://maven.example.com ZONE_ID=<cloudflare-zone> e2e/run-flow.sh 1.0.0
```

The harness knows nothing about any live repository — no defaults, no config file, no lookup. It
publishes where you point it, and refuses to run if you point it nowhere. Credentials come from
the environment too: `AWS_*` for the store, `CLOUDFLARE_API_TOKEN` for purging.

### The live job

`local-flow.sh` covers everything a MinIO can answer, which is almost all of it, and runs on every
pull request. What needs a real CDN — edge cache status, the negative-caching rule, and purge —
is `.github/workflows/live-contracts.yml`. It runs nightly and on demand but never on a pull
request: a forked PR cannot reach the secrets, and untrusted code must not be handed credentials
that can write to a release repository.

Deploy a stack for it — copy `infra/Pulumi.example.yaml` to `infra/Pulumi.<stack>.yaml`, set
`createCiToken: "true"`, `pulumi -C infra up` — then create a `live-contracts` environment on the
GitHub repository and set the following in it. CI reads these and nothing else; it holds no
Pulumi state and no deploy token.

Secrets:

| name | value |
|---|---|
| `R2_ACCESS_KEY_ID` | the minted token's id — `pulumi stack output ciTokenId` |
| `R2_SECRET_ACCESS_KEY` | the sha256 of the token *value*, not the value itself |
| `R2_S3_ENDPOINT` | `pulumi stack output s3Endpoint` |
| `CLOUDFLARE_PURGE_TOKEN` | the token value verbatim — purging is a bearer-token call and cannot ride on the S3 key |
| `TEST_ZONE_ID` | the zone serving the hostname |

Variables:

| name | value |
|---|---|
| `TEST_BUCKET` | the bucket name |
| `TEST_DOMAIN` | the hostname, no scheme |

R2 does not issue S3 credentials separately — it derives them from a Cloudflare API token, so one
minted token supplies three of the five secrets above. The secret key is the only one that needs
computing, and a trailing newline in the hash is the way it usually goes wrong:

```bash
pulumi -C infra stack output ciTokenValue --show-secrets | tr -d '\n' | shasum -a 256
```

The job publishes `0.0.<run-number>`, a version nothing else will claim, so reruns never collide
with an immutable key. That also means the bucket grows by a couple of dozen objects a night;
until `gc` exists, the cleanup is to tear the stack down and redeploy it, which is a few seconds
and costs nothing but the fixtures.

## Status

Early. Published locally only (`sbt publishLocal`); the API will move before it goes to Maven
Central. Licence Apache-2.0.
