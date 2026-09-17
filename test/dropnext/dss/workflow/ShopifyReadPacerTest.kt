package dropnext.dss.workflow

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Success
import dropnext.dss.domain.ShopifyRateBudget
import dropnext.dss.lib.shopify.graphql.ShopifyError
import dropnext.dss.lib.shopify.graphql.ShopifyResult
import dropnext.dss.testutil.helper.failureReason
import dropnext.dss.testutil.helper.successValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test


class ShopifyReadPacerTest {

  /** Records what the pacer would have waited, so no test waits. */
  private val pauses = mutableListOf<Duration>()
  private val pacer = ShopifyReadPacer(floor = 0.5, retries = 2, pause = { pauses.add(it) })

  private fun bucket(available: Double) =
    ShopifyRateBudget(maximumAvailable = 1000.0, currentlyAvailable = available, restoreRate = 50.0)

  /** Each call answers the next of [answers], the last one for every later call; the budget travels with a success. */
  private class Answers(vararg answers: ShopifyResult<ShopifyRateBudget?>) {
    private val queue = answers.toMutableList()
    var calls = 0
      private set

    suspend fun next(): ShopifyResult<ShopifyRateBudget?> {
      calls++
      return if (queue.size > 1) queue.removeAt(0) else queue.single()
    }
  }

  private suspend fun ShopifyReadPacer.readFrom(answers: Answers) = read({ it }) { answers.next() }

  @Test
  fun `a first read waits for nothing and remembers the budget it was answered with`() = runBlocking {
    val answered = pacer.readFrom(Answers(Success(bucket(900.0))))

    assert(answered.successValue() == bucket(900.0))
    assert(pacer.budget == bucket(900.0))
    assert(pauses.isEmpty())
  }

  /** The other half of the bucket is the shop's live traffic's. */
  @Test
  fun `a read after an answer that left the bucket under the floor waits for the refill first`() = runBlocking {
    pacer.readFrom(Answers(Success(bucket(300.0))))

    pacer.readFrom(Answers(Success(bucket(700.0))))

    // 200 points short of half, at 50 a second.
    assert(pauses == listOf(4.seconds))
    assert(pacer.waited == 4.seconds)
  }

  /**
   * Shopify runs a query only when the bucket holds what the query requests: a refill to the floor is not enough for one
   * that costs more than the floor leaves. 600 points are above half, and the 800-point query needs 200 more.
   */
  @Test
  fun `a throttled read is asked again once the bucket holds what the query costs`() = runBlocking {
    val throttled = ShopifyError.GraphqlError(
      "Throttled", codes = listOf("THROTTLED"), rateBudget = bucket(600.0), requestedCost = 800,
    )
    val answers = Answers(Failure(throttled), Success(bucket(10.0)))

    val answered = pacer.readFrom(answers)

    assert(answered.successValue() == bucket(10.0))
    assert(answers.calls == 2)
    assert(pauses == listOf(4.seconds))
  }

  /** Without a word on the bucket, the growing backoff is all there is to go on. */
  @Test
  fun `a read that keeps failing retryably is asked again with a growing backoff and then fails`() = runBlocking {
    val answers = Answers(Failure(ShopifyError.Network("reset")))

    val answered = pacer.readFrom(answers)

    assert(answered.failureReason() == ShopifyError.Network("reset"))
    assert(answers.calls == 3)
    assert(pauses == listOf(1.seconds, 2.seconds))
  }

  @Test
  fun `a failure no retry fixes is answered at once`() = runBlocking {
    val answers = Answers(Failure(ShopifyError.TokenRejected(401)))

    val answered = pacer.readFrom(answers)

    assert(answered.failureReason() == ShopifyError.TokenRejected(401))
    assert(answers.calls == 1)
    assert(pauses.isEmpty())
  }

  /** A refusal reports the bucket as it is now, which is newer than what the last success said. */
  @Test
  fun `the budget a refusal reports replaces the one the last success reported`() = runBlocking {
    pacer.readFrom(Answers(Success(bucket(900.0))))
    val denied = ShopifyError.GraphqlError("Access denied", codes = listOf("ACCESS_DENIED"), rateBudget = bucket(100.0))

    pacer.readFrom(Answers(Failure(denied)))

    assert(pacer.budget == bucket(100.0))
  }
}
