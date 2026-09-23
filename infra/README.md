# infra

A Pulumi program, written in Scala with [Besom](https://virtuslab.github.io/besom/), that
provisions the repository this tool publishes to: an R2 bucket, a custom domain in front of it
with its DNS record, and — each behind a flag — the zone's cache rules and the credential a
release job publishes with.

```bash
cp infra/Pulumi.example.yaml infra/Pulumi.dev.yaml   # then fill in account, zone, domain, bucket
pulumi -C infra up
```

The real stack file is gitignored: it names an account, a zone and a hostname. Every setting is
documented in `Pulumi.example.yaml`.

The stack exports `repositoryUrl`, `bucketName` and `s3Endpoint`, the custom domain's
`domainOwnership` and `domainSsl` status, and, when `createCiToken` is on, `ciTokenId` and
`ciTokenValue`. What a release job does with those is in
[CONTRIBUTING.md](../CONTRIBUTING.md#the-live-job); what the CI token itself may do is in the
[README](../README.md#credentials).

## The deploy token

Read as `CLOUDFLARE_API_TOKEN`, and needed only where this program runs — a publishing job holds
the CI token instead, which carries none of these permissions. The last two rows are needed only
when `createCiToken` is on, and are account-scoped rather than the user-scoped *API Tokens*
groups: the credential this mints is account-owned, so it is read and written through
`/accounts/<id>/tokens`.

| Permission | Scope | For |
|---|---|---|
| Workers R2 Storage: Edit | account | the bucket and its custom domain |
| Zone: Read | zone | resolving the zone |
| DNS: Edit | zone | the custom domain's DNS record |
| Cache Settings: Edit | zone | only with `manageCacheRules` — reading and writing the zone's cache rules |
| Account API Tokens: Read | account | only with `createCiToken` — resolving the CI token's permission groups by name |
| Account API Tokens: Edit | account | only with `createCiToken` — minting it |

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
