package com.patrick.test.jsonpoller

import scala.concurrent.ExecutionContext
import scala.concurrent.Promise
import scala.concurrent.Future
import java.util.concurrent.CancellationException

object FutureUtils {
  def interruptableFuture[T](fun: Future[T] => T)(implicit ex: ExecutionContext): (Future[T], () => Boolean) = {
    val p = Promise[T]()
    val f = p.future
    val lock = new Object
    var currentThread: Thread = null
    def updateCurrentThread(newThread: Thread): Thread = {
      val old = currentThread
      currentThread = newThread
      old
    }
    p tryCompleteWith Future {
      val thread = Thread.currentThread
      lock.synchronized { updateCurrentThread(thread) }
      try fun(f) finally {
        val wasInterrupted = lock.synchronized { updateCurrentThread(null) } ne thread
        //Deal with interrupted flag of this thread in desired
      }
    }

    (f, () => lock.synchronized {
      Option(updateCurrentThread(null)) exists {
        t =>
          t.interrupt()
          p.tryFailure(new CancellationException)
      }
    })
  }
}