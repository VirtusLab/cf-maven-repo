# cf-maven-repo

Publish and manage a Maven repository on S3-compatible object storage — Cloudflare R2, MinIO, S3.

A Maven repository is not a service. It is a naming convention over HTTP GETs: to resolve
`org.example:lib_3:1.0` a resolver asks for
`<repo>/org/example/lib_3/1.0/lib_3-1.0.pom`. Any object store that serves objects over public
HTTPS is therefore a read-only Maven repository, and the object key *is* the repository path.

What that arrangement does *not* give you is everything a registry does on your behalf — checksum
sidecars, `maven-metadata.xml`, immutability, cache correctness. That is what this does.

```bash
cs channel --add https://raw.githubusercontent.com/VirtusLab/cf-maven-repo/master/coursier/apps.json
cs install cf-maven-repo

cf-maven-repo publish --staging ./staged --bucket my-maven
```

That installs a native binary — no JVM, and nothing resolved at install time beyond the version
itself. Binaries are built for x64 and arm64 Linux, Apple silicon and x64 Windows; anywhere else
coursier falls back to a JVM launcher built from the same release. Building from source is in
[CONTRIBUTING.md](CONTRIBUTING.md).

As a library:

```scala
libraryDependencies += "org.virtuslab" %% "cf-maven-repo-core" % "<version>"
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

## Credentials

Two tokens. Reading the repository needs neither — it is public over HTTPS.

**Deploy token** — runs the Pulumi program in `infra/`, and a publishing job never sees it.
[infra/README.md](infra/README.md) lists the permissions it needs.

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

## Status

Early. Not on Maven Central yet, so the API can still move; the release path is in place for when
it settles. [CONTRIBUTING.md](CONTRIBUTING.md) covers testing and releasing. Licence Apache-2.0.
