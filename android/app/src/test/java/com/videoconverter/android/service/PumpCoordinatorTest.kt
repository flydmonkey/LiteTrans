package com.videoconverter.android.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            executor.submit<Boolean> {
                ready.countDown()
                start.await()
                coordinator.requestStart()
            }
        }
        ready.await()
        start.countDown()

        assertEquals(1, starts.count { it.get() })
        executor.shutdown()
    }

    @Test
    fun requestWhilePumpStopsKeepsPumpAlive() {
        val coordinator = PumpCoordinator()
        assertTrue(coordinator.requestStart())
        assertFalse(coordinator.requestStart())

        assertTrue(coordinator.shouldContinue(hasQueuedJob = false))
        assertFalse(coordinator.shouldContinue(hasQueuedJob = false))
        assertTrue(coordinator.requestStart())
    }

    @Test
    fun queuedRecheckKeepsPumpAlive() {
        val coordinator = PumpCoordinator()
        assertTrue(coordinator.requestStart())

        assertTrue(coordinator.shouldContinue(hasQueuedJob = true))
        assertFalse(coordinator.requestStart())
    }
}
