import besom.*
import besom.util.interpolator.*
import besom.api.cloudflare
import besom.api.cloudflare.inputs.*

/** A publicly readable Maven repository on Cloudflare R2.
  *
  * The object key is the repository path, so a bucket plus a hostname is already a working read-only repository; the rest configures
  * caching and write access.
  */
@main def main = Pulumi.run {
  val accountId = config.requireString("accountId")
  val zoneId = config.requireString("zoneId")
  val domain = config.requireString("domain")
  val bucketName = config.getString("bucketName").map(_.getOrElse("maven"))
  val location = config.getString("location") // apac | eeur | enam | weur | wnam | oc; unset = automatic
  val createCiToken = config.getBoolean("createCiToken").map(_.getOrElse(false))

  // Off by default, because this zone's cache phase may not be this program's to own - see the
  // README. When off, nothing here touches the zone's cache configuration at all, and the
  // repository still works: the publisher sets Cache-Control on every object as it uploads it,
  // and Cloudflare honours it.
  val manageCacheRules = config.getBoolean("manageCacheRules").map(_.getOrElse(false))
  // Set to false to mint an upload-only credential. Publishing still works; stale metadata then
  // waits out its 60-second TTL instead of being purged.
  val ciTokenCanPurge = config.getBoolean("ciTokenCanPurge").map(_.getOrElse(true))

  // The resource kinds the CI token's two policies address. Each is both the scope its permission
  // group must declare and the prefix of the resource string that names one object of that kind,
  // which is what keeps the two halves of a policy from drifting apart.
  val R2BucketScope = "com.cloudflare.edge.r2.bucket"
  val ZoneScope = "com.cloudflare.api.account.zone"

  /** Resolves a permission group id by name, at deploy time, rather than carrying one in config or in source.
    *
    * Cloudflare treats the id as the stable key and the name as cosmetic, which is backwards for a program that wants to state what it
    * needs. Looking the id up keeps the *name* in the source, where it can be reviewed against what the token is for.
    *
    * Note the data source: `getAccountApiTokenPermissionGroupsList` reads /accounts/<id>/tokens/permission_groups, the groups an API token
    * can hold. The similarly named `getAccountPermissionGroups` reads /accounts/<id>/iam/permission_groups instead - the groups an account
    * *member* can hold - which is a different and smaller set that contains none of these, and returns an empty list rather than an error.
    *
    * `scope` is checked, not decoration: it is the resource kind the group applies to, and it has to agree with the resource string the
    * policy pairs it with below. It is also the difference that matters most here - "Workers R2 Storage Write" is account-scoped and can
    * create and delete buckets, while "Workers R2 Storage Bucket Item Write" is bucket-scoped and cannot.
    */
  def permissionGroupId(account: Output[String], groupName: String, scope: String): Output[String] =
    for
      found <- cloudflare.getAccountApiTokenPermissionGroupsList(
        cloudflare.GetAccountApiTokenPermissionGroupsListArgs(accountId = account, name = groupName)
      )
      groups <- found.results
      described <- Output.sequence(groups.toList.map { g =>
        for
          name <- g.name
          id <- g.id
          scopes <- g.scopes
        yield (name, id, scopes.toList)
      })
      id <- described.filter((name, _, _) => name == groupName) match
        case (_, id, scopes) :: Nil if scopes.contains(scope) => Output(id)
        case (_, _, scopes) :: Nil                            =>
          Output.fail(
            Exception(
              s"""permission group "$groupName" applies to ${scopes.mkString(", ")}, but this policy needs $scope - """ +
                "refusing to attach it to a resource it does not cover"
            )
          )
        case Nil if described.isEmpty =>
          Output.fail(
            Exception(
              "the account returned no API token permission groups at all - " +
                """the token running Pulumi needs "Account API Tokens: Read" to list them"""
            )
          )
        case Nil =>
          Output.fail(Exception(s"""none of the ${described.size} permission groups returned is named "$groupName""""))
        case many =>
          Output.fail(Exception(s"""${many.size} permission groups are named "$groupName"; refusing to choose"""))
    yield id

  // Config overrides exist so a deployment can pin an id and skip the lookup entirely, which is
  // also the escape hatch if Cloudflare ever renames a group.
  val r2WriteGroupId = config.getString("r2WritePermissionGroupId").flatMap {
    case Some(id) => Output(id)
    case None     => permissionGroupId(accountId, "Workers R2 Storage Bucket Item Write", R2BucketScope)
  }
  val purgeGroupId = config.getString("cachePurgePermissionGroupId").flatMap {
    case Some(id) => Output(id)
    case None     => permissionGroupId(accountId, "Cache Purge", ZoneScope)
  }

  val bucket = cloudflare.R2Bucket(
    "maven",
    cloudflare.R2BucketArgs(
      accountId = accountId,
      name = bucketName,
      location = location,
      storageClass = "Standard"
    )
  )

  // A custom domain rather than the *.r2.dev development URL, which is rate-limited and not
  // intended for production. It is also the only thing consumers configure, so the backend can be
  // replaced behind it without touching any build file.
  val customDomain = cloudflare.R2CustomDomain(
    "maven-domain",
    cloudflare.R2CustomDomainArgs(
      accountId = accountId,
      bucketName = bucket.name,
      domain = domain,
      zoneId = zoneId,
      enabled = true,
      minTls = "1.2"
    )
  )

  // Artifacts are immutable and carry a long Cache-Control set at upload time; metadata is
  // rewritten on every publish and must not be cached for long. These rules make the edge honour
  // that, and pin metadata to a short TTL regardless of the header an upload set.
  //
  // The expressions are mutually exclusive so the result does not depend on rule order within the
  // phase.
  //
  // This resource is the entry point ruleset for the zone's cache phase, and the provider treats
  // an entry point ruleset as the complete contents of that phase. Any cache rule the zone
  // already has must be imported and listed here, or applying this will remove it.
  // Matched by exact file name rather than by substring: an artifact whose own name merely
  // contains "maven-metadata.xml" is immutable like any other, and must not inherit the short TTL.
  val metadataNames = "" :: List(".sha1", ".md5", ".sha256", ".sha512")
  val isMetadata = metadataNames
    .map(suffix => s"""ends_with(http.request.uri.path, "/maven-metadata.xml$suffix")""")
    .mkString("(", " or ", ")")
  val underReleases = """starts_with(http.request.uri.path, "/releases/")"""

  val immutableTtl = 31536000

  /** Cloudflare reads -1 as "do not store this response". */
  def noStore(from: Int, to: Int) = RulesetRuleActionParametersEdgeTtlStatusCodeTtlArgs(
    statusCodeRange = RulesetRuleActionParametersEdgeTtlStatusCodeTtlStatusCodeRangeArgs(from = from, to = to),
    value = -1
  )

  /** Refs this program owns. A rule carrying one is this program's own output from a previous run and is replaced; everything else in the
    * phase belongs to somebody else and is preserved as it stands.
    */
  val ownedRefs = Set("maven_metadata_short_ttl", "maven_artifacts_immutable")

  val ownRules = List(
    RulesetRuleArgs(
      ref = "maven_metadata_short_ttl",
      // The documented per-plan Edge Cache TTL floor - two hours on Free, one on Pro -
      // applies to the legacy setting, not to cache rules: measured on a Free zone, this
      // override expires the object on schedule and overrides the origin's own max-age.
      description = "maven-metadata.xml is the commit point - keep it fresh",
      action = "set_cache_settings",
      expression = p"""(http.host eq "$domain" and $isMetadata)""",
      actionParameters = RulesetRuleActionParametersArgs(
        cache = true,
        edgeTtl = RulesetRuleActionParametersEdgeTtlArgs(mode = "override_origin", default = 60),
        browserTtl = RulesetRuleActionParametersBrowserTtlArgs(mode = "override_origin", default = 60)
      )
    ),
    RulesetRuleArgs(
      ref = "maven_artifacts_immutable",
      description = "a published version is never rewritten",
      action = "set_cache_settings",
      expression = p"""(http.host eq "$domain" and $underReleases and not ($isMetadata))""",
      actionParameters = RulesetRuleActionParametersArgs(
        cache = true,
        // The default TTL matches the Cache-Control the publisher sets on upload, and applies
        // to the success responses that carry it. Every failure response is pinned to
        // no-store: Cloudflare caches a 404 for three minutes by default, which would keep a
        // version invisible after it was published to a resolver that probed for it first,
        // and an origin 5xx would otherwise inherit the one-year default.
        edgeTtl = RulesetRuleActionParametersEdgeTtlArgs(
          mode = "override_origin",
          default = immutableTtl,
          statusCodeTtls = List(noStore(from = 400, to = 499), noStore(from = 500, to = 599))
        ),
        browserTtl = RulesetRuleActionParametersBrowserTtlArgs(mode = "respect_origin")
      )
    )
  )

  /** Everything already in the phase, then this program's rules.
    *
    * Order is the whole reason this is an append: for cache rules the *last* matching rule wins, so putting these last means they decide
    * the outcome for the repository hostname, while leaving every other rule to decide its own.
    */
  val entrypoint: Output[CacheEntrypoint.Existing] =
    zoneId.flatMap { zone =>
      CacheEntrypoint.existing(zone, ownedRefs) match
        case Right(found)  => Output(found)
        case Left(problem) => Output.fail(Exception(problem))
    }

  val mergedRules: Output[List[RulesetRuleArgs]] = entrypoint.map(_.foreign ++ ownRules)

  val cacheRules = Output.when(manageCacheRules) {
    cloudflare.Ruleset(
      "maven-cache-rules",
      cloudflare.RulesetArgs(
        zoneId = zoneId,
        kind = "zone",
        phase = CacheEntrypoint.Phase,
        // The name and description of an entry point this program adopted are left exactly as
        // they were. The provider treats a name change as a new identity and replaces the
        // ruleset, and a replacement is a second entry point for the phase, which Cloudflare
        // refuses - so renaming somebody else's ruleset does not fail cosmetically, it fails the
        // deployment. Only a phase this program created itself carries its own name.
        name = entrypoint.map(_.name.getOrElse("maven cache policy")),
        description = entrypoint.map(_.description.getOrElse("immutable artifacts, short-lived metadata")),
        rules = mergedRules
      ),
      // Kept when the phase holds rules this program did not write. Turning manageCacheRules off,
      // or destroying the stack, otherwise deletes the entry point - and with it somebody else's
      // rules, which is the failure this whole path exists to avoid. The cost of retaining is two
      // leftover rules scoped to a hostname that no longer serves anything; the cost of deleting
      // is theirs. A phase this program created alone has nothing to retain and is removed
      // normally.
      opts(retainOnDelete = entrypoint.map(_.foreign.nonEmpty))
    )
  }

  // Writing objects and purging metadata are the two halves of one publish, so one credential
  // carries both: a policy per resource kind, since a bucket and a zone are addressed
  // differently. Purge is a low-harm capability - the worst it buys an attacker is cache misses -
  // so pairing it with object write costs little, and the separation that matters, between
  // publishing and changing infrastructure, is unaffected.
  val ciPolicies: Output[List[AccountTokenPolicyArgs]] =
    for
      writeGroup <- r2WriteGroupId
      purgeGroup <- purgeGroupId
      canPurge <- ciTokenCanPurge
      account <- accountId
      zone <- zoneId
      bucketName <- bucket.name
    yield
      val write = AccountTokenPolicyArgs(
        effect = "allow",
        permissionGroups = List(AccountTokenPolicyPermissionGroupArgs(id = writeGroup)),
        // R2 buckets are addressed as <account>_<jurisdiction>_<bucket>; buckets created without
        // a jurisdiction are in "default".
        resources = s"""{"$R2BucketScope.${account}_default_$bucketName": "*"}"""
      )
      val purgeOnZone = Option.when(canPurge) {
        AccountTokenPolicyArgs(
          effect = "allow",
          permissionGroups = List(AccountTokenPolicyPermissionGroupArgs(id = purgeGroup)),
          resources = s"""{"$ZoneScope.$zone": "*"}"""
        )
      }
      write :: purgeOnZone.toList

  // The token a release job holds: object write on one bucket and cache purge on one zone, no
  // access to infrastructure. Reads need no credential at all. Off by default, since minting it
  // requires "Account API Tokens: Edit" on whichever token runs this program - the account-scoped
  // group, not the user-scoped one, because this credential is owned by the account.
  //
  // An account token rather than a user one: a user token belongs to the individual who created
  // it and stops working when that user leaves the account, which is not a property a release
  // pipeline should have.
  //
  // Permission group ids come from /client/v4/accounts/<account>/tokens/permission_groups, which
  // needs "Account API Tokens: Read" to call: take "Workers R2 Storage Bucket Item Write", the
  // bucket-scoped group rather than the account-wide "Workers R2 Storage Write" that could
  // create and delete buckets, and "Cache Purge".
  val ciToken: Output[Option[cloudflare.AccountToken]] = Output.when(createCiToken) {
    cloudflare.AccountToken(
      "maven-ci-write",
      cloudflare.AccountTokenArgs(
        accountId = accountId,
        name = "maven-ci-write",
        policies = ciPolicies
      )
    )
  }

  // R2 derives S3 credentials from the token rather than issuing them separately:
  //   AWS_ACCESS_KEY_ID     = token id
  //   AWS_SECRET_ACCESS_KEY = sha256(token value)
  val ciTokenId: Output[Option[besom.types.ResourceId]] = ciToken.flatMap {
    case Some(token) => token.id.map(Option(_))
    case None        => Output(Option.empty[besom.types.ResourceId])
  }
  val ciTokenValue: Output[Option[String]] = ciToken.flatMap {
    case Some(token) => token.value.map(Option(_))
    case None        => Output(Option.empty[String])
  }

  Stack(bucket, customDomain, cacheRules, ciToken).exports(
    repositoryUrl = p"https://$domain/releases",
    bucketName = bucket.name,
    s3Endpoint = accountId.map(id => s"https://$id.r2.cloudflarestorage.com"),
    domainOwnership = customDomain.status.map(_.ownership),
    domainSsl = customDomain.status.map(_.ssl),
    ciTokenId = ciTokenId,
    ciTokenValue = ciTokenValue
  )
}
