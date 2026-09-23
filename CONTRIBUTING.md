# Contributing

## Testing

```bash
sbt testFull      # unit, publisher recovery, and the S3 contract against MinIO
sbt cli/assembly  # the runnable jar
```

`testFull` rather than `test`: in sbt 2 `test` means `testQuick`, which skips what a cache says has
already passed. The assembly jar lands in `target/out/jvm/*/cf-maven-repo-cli/cf-maven-repo.jar`,
which is how the e2e scripts invoke the publisher and what the release builds turn into native
binaries.

The S3 contract suite starts its own MinIO through Testcontainers — [Silo](https://github.com/pgsty/silo),
the maintained fork, since MinIO no longer publishes images — and checks the parts no in-memory
stand-in can: conditional writes, the error mapping around them, and paginated listing.
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

## The native gate

Releases ship native binaries, so a pull request has to prove they still build and work before it
can be merged. Add the **`build-native`** label: `.github/workflows/native-gate.yml` then builds
all four platforms and smoke-tests each one.

Mark one check required — **`native / gate`** — and merging is impossible until that label has been
applied to the commit being merged. Pushing new commits strips the label again, so approval is
given for the commit that actually merges rather than inherited from an older one. A pull request
that has not been labelled leaves the check at "Expected", which blocks the merge.

Require `native / gate`, never the bare `native`. A job skipped for want of the label reports
*success*, so requiring the caller's name would unlock exactly the merge it is meant to hold.
`native / gate` lives inside the called workflow, so it exists only when a build really ran, and
it passes only if every platform did.

The smoke test is the point of the gate, not the build. A native image drops things the JVM build
has — URL protocol handlers, TLS, XML parsing — and every one of those failures still prints a
correct `--help`. So `e2e/native-smoke.sh` publishes to a real store, reads the objects back, and
checks that the binary fails to *connect* rather than failing to parse its own endpoint:

```bash
sbt cli/assembly
native-image --no-fallback --enable-url-protocols=http,https -march=compatibility \
  -jar target/out/jvm/*/cf-maven-repo-cli/cf-maven-repo.jar -o cf-maven-repo
e2e/native-smoke.sh ./cf-maven-repo          # --lite where there is no Docker
```

Either build can drive the whole harness: set `CLI_BIN` to a native binary and `e2e/local-flow.sh`
runs the full resolver matrix against it instead of the jar.

## Releasing

A release is a git tag. `sbt-ci-release` reads the version from it, signs every artifact, and
uploads them through sbt 2's own Sonatype staging to the Central Portal:

```bash
git tag -a v0.1.0 -m v0.1.0 && git push origin v0.1.0
```

`.github/workflows/release.yml` does the rest. Nothing publishes from a branch, and the version is
never written in `build.sbt` — the tag is the single place a version is stated.

A tag produces two halves, and `cs install` needs both: the library on Central, and a native
binary per platform attached to the GitHub release. `coursier/apps.json` points at them through
`${version}`, so it names no version and needs no edit per release.

The native builds take the assembly jar and run `native-image` on it. Two flags are not optional:
`--enable-url-protocols=http,https`, without which the S3 client fails its first call with "was
not a valid URI" because the protocol handler was never compiled in, and `-march=compatibility`,
so a release binary runs on any CPU of its architecture. x64 Linux links statically against musl
and needs no system libc; arm64 Linux has no musl toolchain, so it is static except for glibc and
builds on the oldest runner image to keep that floor low.

The job needs four repository secrets:

| Secret | What it is |
|---|---|
| `PGP_SECRET` | the base64 of the private key that signs the artifacts |
| `PGP_PASSPHRASE` | that key's passphrase |
| `SONATYPE_USERNAME` | the user-token name from the Central Portal, not the account login |
| `SONATYPE_PASSWORD` | that token's password |

Central requires signatures, sources and javadoc jars for every module. `publish` produces the
whole bundle locally, under `target/sona-staging`, so it can be inspected before a tag exists.
