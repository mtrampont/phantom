/*
 * Copyright 2013 - 2017 Outworkers Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.outworkers.phantom.streams.lib

import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicReference

/**
  * Provides mutual exclusion without blocking.
  *
  * {{{
  * // The following two tasks will run one at a time, but in any order.
  * val mutex = new NonBlockingMutex()
  * Future {
  *   mutex.exclusive { task1() }
  * }
  * Future {
  *   mutex.exclusive { task2() }
  * }
  * }}}
  *
  * A queue of operations is maintained internally and updated atomically.
  * If `exclusive` is called while no other operations are running then
  * that operation will run immediately. If another operation is running
  * then the new operation will be added to the end of the queue, scheduling
  * the operation to run later without blocking the current thread. When an
  * operation finishes running it looks at the queue and runs any tasks
  * that are enqueued.
  *
  * Because operations can run on other threads it is important that they
  * run very quickly. If any expensive work needs to be done then it the operation
  * should schedule the work to run asynchronously.
  */
private[streams] final class NonBlockingMutex {

  /**
    * Schedule an operation to run with exclusive access to the mutex. If the
    * mutex is uncontended then the operation will run immediately. If not, it
    * will be enqueued and the method will yield immediately. The operation will
    * run later on another thread.
    *
    * Because operations can run on other threads it is important that they
    * run very quickly. If any expensive work needs to be done then it the operation
    * should schedule the work to run asynchronously.
    *
    * @param body The body of the operation to run.
    */
  def exclusive(body: => Unit): Unit = {
    schedule(() => body)
  }

  private type Op = () => Unit

  private[this] val state = new AtomicReference[Vector[Op]](NonBlockingMutex.nullValue)

  @tailrec
  private[this] def schedule(op: Op): Unit = {
    val prevState = state.get
    val newState = prevState match {
      case NonBlockingMutex.nullValue => Vector.empty // This is very cheap because Vector.empty is only allocated once
      case pending => pending :+ op
    }
    if (state.compareAndSet(prevState, newState)) {
      prevState match {
        case NonBlockingMutex.nullValue =>
          // We've update the state to say that we're running an op,
          // so we need to actually start it running.
          executeAll(op)
        case _ =>
      }
    } else {
      // Try again
      schedule(op)
    }
  }

  @tailrec
  private def executeAll(op: Op): Unit = {
    op.apply()
    val nextOp = dequeueNextOpToExecute()
    nextOp match {
      case None => ()
      case Some(o) => executeAll(o)
    }
  }

  @tailrec
  private def dequeueNextOpToExecute(): Option[Op] = {
    val prevState = state.get
    val (newState, nextOp) = prevState match {
      case NonBlockingMutex.nullValue => throw new IllegalStateException("When executing, must have a queue of pending elements")
      case pending if pending.isEmpty => (None.orNull, None)
      case pending => (pending.tail, Some(pending.head))
    }
    if (state.compareAndSet(prevState, newState)) nextOp else dequeueNextOpToExecute()
  }
}

object NonBlockingMutex {
  private[phantom] val nullValue = None.orNull
}
