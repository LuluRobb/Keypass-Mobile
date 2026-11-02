package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.platform.session.LockReason
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LockTrackerTest {

    private fun locked(reason: LockReason?) = SessionView.Locked(reason)

    @Test
    fun `the state present at creation is no lock, whatever its reason`() {
        for (reason in listOf(null, LockReason.User, LockReason.TaskRemoved, LockReason.ServiceRefused)) {
            val tracker = LockTracker(locked(reason))
            assertNull(tracker.onSession(locked(reason)), "$reason")
        }
    }

    @Test
    fun `a lock after an unlocked session counts`() {
        val tracker = LockTracker(locked(null))
        assertNull(tracker.onSession(SessionView.Unlocking))
        assertNull(tracker.onSession(unlockedView()))
        assertEquals(locked(LockReason.User), tracker.onSession(locked(LockReason.User)))

        assertNull(tracker.onSession(unlockedView()))
        assertEquals(locked(LockReason.Idle(IdleTimeout.MINUTES_2)), tracker.onSession(locked(LockReason.Idle(IdleTimeout.MINUTES_2))))

        // Without a reason it is still the end of an open session.
        assertNull(tracker.onSession(unlockedView()))
        assertEquals(locked(null), tracker.onSession(locked(null)))
    }

    @Test
    fun `a session that starts unlocked is locked once`() {
        val tracker = LockTracker(unlockedView())
        assertEquals(locked(LockReason.Notification), tracker.onSession(locked(LockReason.Notification)))
        // Repeated values of the same lock (settings changed) do not count again.
        assertNull(tracker.onSession(locked(LockReason.Notification)))
    }

    @Test
    fun `a lock right after the unlock counts although Unlocked was never seen`() {
        val tracker = LockTracker(locked(null))
        assertEquals(locked(LockReason.ServiceRefused), tracker.onSession(locked(LockReason.ServiceRefused)))
        assertNull(tracker.onSession(locked(LockReason.ServiceRefused)))

        // Through Unlocking, too.
        val other = LockTracker(locked(null))
        assertNull(other.onSession(SessionView.Unlocking))
        assertEquals(locked(LockReason.ServiceRefused), other.onSession(locked(LockReason.ServiceRefused)))
    }

    @Test
    fun `the end of a failed unlock is no lock`() {
        val tracker = LockTracker(locked(null))
        assertNull(tracker.onSession(SessionView.Unlocking))
        assertNull(tracker.onSession(locked(null)))
        assertNull(tracker.onSession(SessionView.Unlocking))
        assertNull(tracker.onSession(locked(null)))
    }

    @Test
    fun `a failed unlock after a lock does not repeat that lock, a new lock with the same reason counts again`() {
        val tracker = LockTracker(unlockedView())
        assertEquals(locked(LockReason.ServiceRefused), tracker.onSession(locked(LockReason.ServiceRefused)))

        assertNull(tracker.onSession(SessionView.Unlocking))
        assertNull(tracker.onSession(locked(null)))

        // The next unlock and lock arrive conflated: Locked(null) -> Locked(ServiceRefused).
        assertEquals(locked(LockReason.ServiceRefused), tracker.onSession(locked(LockReason.ServiceRefused)))
    }

    @Test
    fun `a swipe-away is still reported as a lock, the message mapping keeps it silent`() {
        val tracker = LockTracker(unlockedView())
        val lock = tracker.onSession(locked(LockReason.TaskRemoved))
        assertEquals(locked(LockReason.TaskRemoved), lock)
        assertNull(lockMessage(lock?.reason))
    }
}
