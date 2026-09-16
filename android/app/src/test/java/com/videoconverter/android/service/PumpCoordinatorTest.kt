package com.videoconverter.android.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpCoordinatorTest {
    @Test
    fun concurrentStartsLaunchExactlyOnePump() {
        val coordinator = PumpCoordinator()
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(16)

        val starts = (1..16).map {
            executor.submit<Long?> {
                ready.countDown()
                start.await()
                coordinator.requestStart()
            }
        }
        ready.await()
        start.countDown()

        assertEquals(1, starts.count { it.get() != null })
        executor.shutdown()
    }

    @Test
    fun requestWhilePumpStopsKeepsPumpAlive() {
        val coordinator = PumpCoordinator()
        val generation = coordinator.requestStart()
        assertNotNull(generation)
        assertNull(coordinator.requestStart())

        assertTrue(coordinator.shouldContinue(generation!!, hasQueuedJob = false))
        assertFalse(coordinator.shouldContinue(generation, hasQueuedJob = false))
        assertNull(coordinator.requestStart())
        coordinator.finish(generation)
        assertNotNull(coordinator.requestStart())
    }

    @Test
    fun queuedRecheckKeepsPumpAlive() {
        val coordinator = PumpCoordinator()
        val generation = coordinator.requestStart()
        assertNotNull(generation)

        assertTrue(coordinator.shouldContinue(generation!!, hasQueuedJob = true))
        assertNull(coordinator.requestStart())
    }

    @Test
    fun failedPumpCanStartAgainAfterFinallyReset() {
        val coordinator = PumpCoordinator()
        val generation = coordinator.requestStart()
        assertNotNull(generation)

        coordinator.finish(generation!!)

        assertNotNull(coordinator.requestStart())
    }

    @Test
    fun oldPumpFinallyDoesNotClearNewPumpRunningFlag() {
        val coordinator = PumpCoordinator()
        val oldGeneration = coordinator.requestStart()
        assertNotNull(oldGeneration)

        assertFalse(coordinator.shouldContinue(oldGeneration!!, hasQueuedJob = false))
        assertNull(coordinator.requestStart())
        coordinator.finish(oldGeneration)

        val newGeneration = coordinator.requestStart()
        assertNotNull(newGeneration)
        coordinator.finish(oldGeneration)

        assertNull(coordinator.requestStart())
        coordinator.finish(newGeneration!!)
        assertNotNull(coordinator.requestStart())
    }

    @Test
    fun cancelThenRetryExecuteInArrivalOrder() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val finished = CountDownLatch(2)
        var status = "Queued"
        val commands = SerialServiceCommands<String>(scope) { command, _ ->
            when (command) {
                "cancel" -> status = "Cancelled"
                "retry" -> if (status == "Cancelled") status = "Queued"
            }
            finished.countDown()
        }

        commands.dispatch(startId = 1, command = "cancel")
        commands.dispatch(startId = 2, command = "retry")

        finished.await()
        assertEquals("Queued", status)
        scope.cancel()
    }

    @Test
    fun emptyQueueCommandUsesLatestStartId() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val finished = CountDownLatch(3)
        var emptyQueueStartId: Int? = null
        val commands = SerialServiceCommands<String>(scope) { command, start ->
            if (command == "empty") emptyQueueStartId = start.startId
            finished.countDown()
        }

        commands.dispatch(startId = 7, command = "start")
        commands.dispatch(startId = 11, command = "cancel")
        commands.dispatchInternal("empty")

        finished.await()
        assertEquals(11, emptyQueueStartId)
        scope.cancel()
    }
}
