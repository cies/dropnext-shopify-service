package dropnext.dss.testutil.helper

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success


/**
 * What a [Success] carries, or a failure naming what came instead.
 *
 * `(result as Success).value` answers a wrong result with a `ClassCastException`, which is the one moment a reader
 * most wants to read the error the result carries. These name it, and they read as one expression, so the assertion
 * can stay a single fact about the value.
 */
fun <T, E> Result<T, E>.successValue(): T = when (this) {
  is Success -> value
  is Failure -> error("expected a Success, got a Failure carrying: $reason")
}

/** What a [Failure] carries; the twin of [successValue]. */
fun <T, E> Result<T, E>.failureReason(): E = when (this) {
  is Failure -> reason
  is Success -> error("expected a Failure, got a Success carrying: $value")
}
