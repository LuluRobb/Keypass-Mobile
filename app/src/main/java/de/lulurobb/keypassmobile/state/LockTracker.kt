package de.lulurobb.keypassmobile.state

/**
 * Finds the locks the UI has to react to (reset the unlock screen, show the lock message).
 * Feed it every [SessionView] value in order, the one present at creation first.
 *
 * The platform may publish Unlocked and lock right away (foreground service refused); a StateFlow
 * conflates that to Locked(ServiceRefused) alone. So a lock counts when the session was seen
 * Unlocked before, or when it carries a reason that was not handled yet. Not a lock: the state
 * present at creation (the reason of an earlier lock was shown by an earlier screen), and the end of
 * a failed unlock (reason null by contract; a repeated reason is the same lock again).
 */
internal class LockTracker(initial: SessionView) {
    private var wasUnlocked = initial is SessionView.Unlocked
    private var lastLocked = initial as? SessionView.Locked

    /** The state to handle as a lock now, null for everything else. */
    fun onSession(session: SessionView): SessionView.Locked? = when (session) {
        is SessionView.Unlocked -> {
            wasUnlocked = true
            null
        }
        SessionView.Unlocking -> null
        is SessionView.Locked -> {
            val real = wasUnlocked || (session.reason != null && session != lastLocked)
            wasUnlocked = false
            lastLocked = session
            session.takeIf { real }
        }
    }
}
