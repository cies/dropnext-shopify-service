package dropnext.dss.boot.warmup

import kotlin.concurrent.thread
import kotlin.system.exitProcess


/**
 * Ends the process with status `1`, the reason on stderr: a task whose shared secret the other side refuses must never
 * join the load balancer's rotation, and an exit is what gets ECS to replace it (and a rolling deploy to keep the old
 * task serving). The log line saying why is `startWarmUp`'s, written before this is called.
 *
 * The exit runs on a thread of its own. `exitProcess` blocks its caller until the shutdown hooks are done, and Ktor's
 * hook waits for the application's coroutines, this one among them; called from here it would hold the shutdown up
 * until Ktor's timeout, and with it the Logflare drain that ships the line saying why.
 */
fun exitRefusingTraffic(reason: String) {
  System.err.println("dropnext-shopify-service refuses traffic and exits: $reason")
  thread(name = "refuse-traffic-exit") { exitProcess(1) }
}
