import besom.api.cloudflare.inputs.*
import sttp.client4.quick.*

import scala.util.control.NonFatal

/** Reads the cache rules a zone already has, so this program can add its own without removing them.
  *
  * Cloudflare allows one entry point ruleset per phase per zone and a write replaces its entire rule list, so "append a rule" does not
  * exist as an operation. Adding one safely means reading what is there and writing all of it back.
  *
  * Read through a plain API call rather than the provider's `getRuleset` data source, for a reason that is not stylistic: the generated
  * result type makes every field non-optional, while Cloudflare returns only the fields a rule actually set. A rule read that way cannot
  * tell "the author left this unset" from "this is the zero value", and writing it back would silently give the rule settings nobody chose.
  * Parsing the response here keeps that distinction, which is what lets the conversion below refuse to guess.
  */
object CacheEntrypoint:

  /** The phase holding a zone's cache rules. */
  val Phase = "http_request_cache_settings"

  /** Rule keys this program understands. `id`, `version` and `last_updated` are server-assigned and read-only, so they are accepted and
    * dropped rather than written back.
    */
  private val KnownRuleKeys =
    Set("action", "expression", "description", "enabled", "ref", "action_parameters", "id", "version", "last_updated")

  /** Action parameters this program can reproduce exactly. `cache_key` is deliberately absent: its custom-key form is deep, is gated behind
    * paid plans, and could not be exercised here, so a rule using one stops the deployment instead of being rewritten on a guess.
    */
  private val KnownParameterKeys = Set(
    "cache",
    "edge_ttl",
    "browser_ttl",
    "serve_stale",
    "respect_strong_etags",
    "origin_error_page_passthru",
    "origin_cache_control",
    "additional_cacheable_ports",
    "read_timeout",
    "cache_reserve"
  )

  private final class Unsupported(message: String) extends Exception(message)

  /** What the zone's cache phase already holds.
    *
    * @param rulesetId
    *   the existing entry point, if the zone has one. Cloudflare refuses a second entry point for a phase, so this program has to adopt the
    *   one that is there rather than create its own.
    * @param name
    *   its name, which has to be kept as it is: the provider treats a name change as a new identity and replaces the ruleset, and creating
    *   a replacement for a phase that already has one is exactly what Cloudflare refuses.
    * @param foreign
    *   the rules in it that this program does not own, in their original order.
    */
  final case class Existing(rulesetId: Option[String], name: Option[String], description: Option[String], foreign: List[RulesetRuleArgs])

  /** The rules already in the zone's cache phase that this program does not own, in their original order.
    *
    * `Right(Nil)` covers both an empty phase and a zone that has no entry point at all. A `Left` means the zone holds something this cannot
    * reproduce faithfully, and the caller is expected to stop rather than continue.
    */
  def existing(zoneId: String, ownedRefs: Set[String]): Either[String, Existing] =
    for
      token <- sys.env
        .get("CLOUDFLARE_API_TOKEN")
        .filter(_.nonEmpty)
        .toRight(
          "CLOUDFLARE_API_TOKEN is not set, and merging into an existing cache ruleset reads the zone directly. " +
            "Export it, or set manageCacheRules to false."
        )
      body <- fetch(zoneId, token)
      rules <- convertAll(body, ownedRefs)
    yield Existing(
      rulesetId = body.flatMap(_.obj.get("id")).map(_.str),
      name = body.flatMap(_.obj.get("name")).map(_.str).filter(_.nonEmpty),
      description = body.flatMap(_.obj.get("description")).map(_.str).filter(_.nonEmpty),
      foreign = rules
    )

  private def fetch(zoneId: String, token: String): Either[String, Option[ujson.Value]] =
    val response =
      try
        Right(
          quickRequest
            .get(uri"https://api.cloudflare.com/client/v4/zones/$zoneId/rulesets/phases/$Phase/entrypoint")
            .header("Authorization", s"Bearer $token")
            .send()
        )
      catch case NonFatal(t) => Left(s"could not read the zone's cache ruleset: ${t.getMessage}")

    response.flatMap { r =>
      // A zone with no cache rules yet has no entry point, which the API reports as a 404 rather than an empty one.
      if r.code.code == 404 then Right(None)
      else
        val json = ujson.read(r.body)
        if json.obj.get("success").exists(_.bool) then Right(Some(json("result")))
        else
          val detail = json.obj
            .get("errors")
            .map(_.arr.flatMap(_.obj.get("message")).map(_.str).mkString("; "))
            .filter(_.nonEmpty)
            .getOrElse(s"HTTP ${r.code.code}")
          Left(s"could not read the zone's cache ruleset: $detail")
    }

  private def convertAll(body: Option[ujson.Value], ownedRefs: Set[String]): Either[String, List[RulesetRuleArgs]] =
    try
      val rules = body.toList.flatMap(_.obj.get("rules")).flatMap(_.arr)
      Right(
        rules.zipWithIndex
          .filterNot((rule, _) => rule.obj.get("ref").map(_.str).exists(ownedRefs))
          .map((rule, index) => convert(rule, describe(rule, index)))
          .toList
      )
    catch case e: Unsupported => Left(e.getMessage)

  private def describe(rule: ujson.Value, index: Int): String =
    rule.obj.get("description").map(_.str).filter(_.nonEmpty) match
      case Some(text) => s"""existing cache rule "$text""""
      case None       => s"existing cache rule #${index + 1}"

  private def reject(js: ujson.Value, known: Set[String], where: String): Unit =
    js.obj.keys.filterNot(known).toList.sorted match
      case Nil => ()
      case bad =>
        throw Unsupported(
          s"$where uses ${bad.mkString(", ")}, which this program cannot reproduce exactly. " +
            "Move that rule into infra/Main.scala, or set manageCacheRules to false and manage the zone's cache rules by hand."
        )

  private def convert(js: ujson.Value, where: String): RulesetRuleArgs =
    reject(js, KnownRuleKeys, where)
    val action = js("action").str
    if action != "set_cache_settings" then
      throw Unsupported(s"""$where has the action "$action"; only set_cache_settings rules can be preserved here""")
    RulesetRuleArgs(
      action = action,
      expression = js("expression").str,
      description = js.obj.get("description").map(_.str),
      enabled = js.obj.get("enabled").map(_.bool),
      // Kept so the rule holds its identity across writes rather than being reissued as a new one.
      ref = js.obj.get("ref").map(_.str),
      actionParameters = js.obj.get("action_parameters").map(parameters(_, where))
    )

  private def parameters(js: ujson.Value, where: String): RulesetRuleActionParametersArgs =
    reject(js, KnownParameterKeys, s"$where")
    RulesetRuleActionParametersArgs(
      cache = js.obj.get("cache").map(_.bool),
      edgeTtl = js.obj.get("edge_ttl").map(edgeTtl(_, where)),
      browserTtl = js.obj.get("browser_ttl").map(browserTtl(_, where)),
      serveStale = js.obj.get("serve_stale").map(serveStale(_, where)),
      respectStrongEtags = js.obj.get("respect_strong_etags").map(_.bool),
      originErrorPagePassthru = js.obj.get("origin_error_page_passthru").map(_.bool),
      originCacheControl = js.obj.get("origin_cache_control").map(_.bool),
      additionalCacheablePorts = js.obj.get("additional_cacheable_ports").map(_.arr.map(_.num.toInt).toList),
      readTimeout = js.obj.get("read_timeout").map(_.num.toInt),
      cacheReserve = js.obj.get("cache_reserve").map(cacheReserve(_, where))
    )

  private def edgeTtl(js: ujson.Value, where: String): RulesetRuleActionParametersEdgeTtlArgs =
    reject(js, Set("mode", "default", "status_code_ttl"), s"$where edge TTL")
    RulesetRuleActionParametersEdgeTtlArgs(
      mode = js("mode").str,
      default = js.obj.get("default").map(_.num.toInt),
      statusCodeTtls = js.obj.get("status_code_ttl").map(_.arr.map(statusCodeTtl(_, where)).toList)
    )

  private def statusCodeTtl(js: ujson.Value, where: String): RulesetRuleActionParametersEdgeTtlStatusCodeTtlArgs =
    reject(js, Set("status_code", "status_code_range", "value"), s"$where status code TTL")
    RulesetRuleActionParametersEdgeTtlStatusCodeTtlArgs(
      statusCode = js.obj.get("status_code").map(_.num.toInt),
      statusCodeRange = js.obj.get("status_code_range").map(statusCodeRange(_, where)),
      value = js("value").num.toInt
    )

  private def statusCodeRange(js: ujson.Value, where: String): RulesetRuleActionParametersEdgeTtlStatusCodeTtlStatusCodeRangeArgs =
    reject(js, Set("from", "to"), s"$where status code range")
    RulesetRuleActionParametersEdgeTtlStatusCodeTtlStatusCodeRangeArgs(
      from = js.obj.get("from").map(_.num.toInt),
      to = js.obj.get("to").map(_.num.toInt)
    )

  private def browserTtl(js: ujson.Value, where: String): RulesetRuleActionParametersBrowserTtlArgs =
    reject(js, Set("mode", "default"), s"$where browser TTL")
    RulesetRuleActionParametersBrowserTtlArgs(
      mode = js("mode").str,
      default = js.obj.get("default").map(_.num.toInt)
    )

  private def serveStale(js: ujson.Value, where: String): RulesetRuleActionParametersServeStaleArgs =
    reject(js, Set("disable_stale_while_updating"), s"$where serve-stale")
    RulesetRuleActionParametersServeStaleArgs(
      disableStaleWhileUpdating = js.obj.get("disable_stale_while_updating").map(_.bool)
    )

  private def cacheReserve(js: ujson.Value, where: String): RulesetRuleActionParametersCacheReserveArgs =
    reject(js, Set("eligible", "minimum_file_size"), s"$where cache reserve")
    RulesetRuleActionParametersCacheReserveArgs(
      eligible = js("eligible").bool,
      minimumFileSize = js.obj.get("minimum_file_size").map(_.num.toInt)
    )
