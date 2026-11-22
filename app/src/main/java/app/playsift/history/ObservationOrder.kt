package app.playsift.history

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val observationLock = Mutex()

/**
 * Runs [observe], which reads the player's counts and records them in the history, with no other observation in between.
 * Every caller shares this one lock, so an older read can never be recorded after a newer one and look like a player reset.
 */
suspend fun <T> inObservationOrder(observe: suspend () -> T): T = observationLock.withLock { observe() }
