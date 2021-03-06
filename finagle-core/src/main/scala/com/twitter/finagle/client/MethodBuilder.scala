package com.twitter.finagle.client

import com.twitter.finagle.{Filter, Stack, Service, ServiceFactory}
import com.twitter.finagle.param
import com.twitter.finagle.service.TimeoutFilter

private[finagle] object MethodBuilder {

  /**
   * Note that metrics will be scoped (e.g. "clnt/your_client_label")
   * to the `withLabel` setting (from [[param.Label]]). If that is
   * not set, `dest` is used.
   *
   * @param dest where requests are dispatched to.
   *             See the [[http://twitter.github.io/finagle/guide/Names.html user guide]]
   *             for details on destination names.
   */
  def from[Req, Rep](
    dest: String,
    stackClient: StackClient[Req, Rep]
  ): MethodBuilder[Req, Rep] = {
    val needsTotalTimeoutModule =
      stackClient.stack.contains(TimeoutFilter.totalTimeoutRole)
    val service: Service[Req, Rep] = stackClient
      .withStack(modified(stackClient.stack))
      .withParams(stackClient.params)
      .newService(dest, param.Label.Default)
    new MethodBuilder[Req, Rep](
      service,
      stackClient.params,
      Config.create(stackClient.params, needsTotalTimeoutModule))
  }

  private[this] def modified[Req, Rep](
    stack: Stack[ServiceFactory[Req, Rep]]
  ): Stack[ServiceFactory[Req, Rep]] = {
    // this is managed directly by us, so that we can put it in the right location
    stack.remove(TimeoutFilter.totalTimeoutRole)
  }

  private object Config {
    def create[Req, Rep](
      params: Stack.Params,
      stackHadTotalTimeout: Boolean
    ): Config[Req, Rep] = {
      Config(
        MethodBuilderRetry.newConfig(params),
        MethodBuilderTimeout.Config(stackHadTotalTimeout))
    }
  }

  private[client] case class Config[Req, Rep](
      retry: MethodBuilderRetry.Config[Req, Rep],
      timeout: MethodBuilderTimeout.Config)

}

/**
 * '''Experimental:''' This API is under construction.
 */
private[finagle] class MethodBuilder[Req, Rep] private (
    service: Service[Req, Rep],
    private[client] val params: Stack.Params,
    private[client] val config: MethodBuilder.Config[Req, Rep]) { self =>
  import MethodBuilder._

  //
  // Configuration
  //

  /**
   * Configure the application-level retry policy.
   *
   * For example, retrying on `Exception` responses:
   * {{{
   * import com.twitter.finagle.client.MethodBuilder
   * import com.twitter.finagle.service.{ReqRep, ResponseClass}
   * import com.twitter.util.Throw
   *
   * val builder: MethodBuilder[Int, Int] = ???
   * builder.withRetry.forClassifier {
   *   case ReqRep(_, Throw(_)) => ResponseClass.RetryableFailure
   * }
   * }}}
   *
   * Defaults to using the client's [[com.twitter.finagle.service.ResponseClassifier]]
   * to retry failures
   * [[com.twitter.finagle.service.ResponseClass.RetryableFailure marked as retryable]].
   *
   * @see [[MethodBuilderRetry]]
   */
  val withRetry: MethodBuilderRetry[Req, Rep] =
    new MethodBuilderRetry[Req, Rep](this)

  /**
   * Configure the timeouts.
   *
   * For example, a total timeout of 200 milliseconds:
   * {{{
   * import com.twitter.conversions.time._
   * import com.twitter.finagle.client.MethodBuilder
   *
   * val builder: MethodBuilder[Int, Int] = ???
   * builder.withTimeout.total(200.milliseconds)
   * }}}
   *
   * Defaults to having no timeouts set.
   *
   * @see [[MethodBuilderTimeout]]
   */
  val withTimeout: MethodBuilderTimeout[Req, Rep] =
    new MethodBuilderTimeout[Req, Rep](this)

  //
  // Build
  //

  /**
   * Create a [[Service]] from the current configuration.
   *
   * @param name used for scoping metrics
   */
  def newService(name: String): Service[Req, Rep] =
    filter(name).andThen(service)

  //
  // Internals
  //

  private[client] def withConfig(config: Config[Req, Rep]): MethodBuilder[Req, Rep] =
    new MethodBuilder(self.service, self.params, config)

  private[this] def filter(name: String): Filter[Req, Rep, Req, Rep] = {
    // Ordering of filters:
    // Requests start at the top and traverse down.
    // Responses flow back from the bottom up.
    //
    // - Logical Stats (TODO)
    // - Total Timeout
    // - Retries
    // - Service (Finagle client's stack, including Per Request Timeout (TODO))

    val stats = params[param.Stats].statsReceiver.scope(name)

    val totalTimeoutFilter = withTimeout.totalFilter
    val retryFilter = withRetry.filter(stats)

    totalTimeoutFilter
      .andThen(retryFilter)
  }

}
