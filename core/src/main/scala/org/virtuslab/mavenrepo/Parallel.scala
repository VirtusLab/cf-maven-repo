package org.virtuslab.mavenrepo

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{Callable, ExecutorService}

/** Bounded-parallelism map that stops handing out work after the first failure.
  *
  * The pool comes from the caller, so a run that spans several calls builds one rather than one per call, and the code that creates it is
  * the code that shuts it down. Parallelism is whatever the pool provides.
  *
  * A fixed pool rather than virtual threads: the SDK's URL-connection client is built on `HttpURLConnection`, whose synchronized internals
  * pin carrier threads.
  */
object Parallel:

  def map[A, B, E](items: Seq[A])(f: A => Either[E, B])(using pool: ExecutorService): Either[E, Seq[B]] =
    if items.isEmpty then Right(Seq.empty)
    else
      val failed = AtomicBoolean(false)
      // Work already in flight is left to finish: cancelling a request mid-write would leave
      // its outcome unknowable, which is worse than one extra object. Queued work is dropped.
      val task: A => Callable[Option[Either[E, B]]] = (a: A) =>
        () =>
          if failed.get() then None
          else
            val result = f(a)
            if result.isLeft then failed.set(true)
            Some(result)

      val results = items.map(a => pool.submit(task(a))).map(_.get()).flatten
      results.collectFirst { case Left(e) => e } match
        case Some(e) => Left(e)
        case None    => Right(results.collect { case Right(b) => b })
