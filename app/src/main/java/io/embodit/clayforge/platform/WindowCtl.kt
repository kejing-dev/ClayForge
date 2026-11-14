package io.embodit.clayforge.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.clayforge.domain.LevelGate

/**
 * Opens / reopens the control window from the Stage (native-window-move.md §3.3, §3.5, §7 item 9; beat-pad WindowCtl):
 * every open first asks the [LevelGate] (head level for 300 ms); the scene feeds the head pitch every frame. Reopen =
 * close, wait for ON_DESTROY, open: the system then puts the window straight ahead of the current head.
 */
class WindowCtl {
    val gate = LevelGate()
    var levelHint by mutableStateOf(false); private set
    var panelState by mutableStateOf("never")
    val open: Boolean get() = panelState in setOf("ON_CREATE", "ON_START", "ON_RESUME", "ON_PAUSE")
    var everOpened by mutableStateOf(false); private set
    var exiting by mutableStateOf(false)
    /** +1 on every open. */
    @Volatile var openSeq = 0; private set
    @Volatile var openWhy = ""; private set

    private var openFn: (() -> Unit)? = null
    private var closeFn: (() -> Unit)? = null
    private var reopenPending = false
    private var lastStepMs = 0L
    private val main = Handler(Looper.getMainLooper())

    fun attach(open: () -> Unit, close: () -> Unit) { openFn = open; closeFn = close }
    fun detach() { openFn = null; closeFn = null; gate.cancel(); main.removeCallbacks(watchdog) }

    fun request(why: String) {
        gate.ask(why, SystemClock.elapsedRealtime())
        CfLog.i("WINDOW ask why=$why state=$panelState")
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, 3000)
    }

    /** [打开面板] / reopen: close if open, wait for ON_DESTROY, then a level-gated open ahead of the head. */
    fun reopen(why: String) {
        if (!open) request(why) else { reopenPending = true; CfLog.i("WINDOW close why=$why"); closeFn?.invoke() }
    }

    /** [叫回面前]: put the window away now (the scene re-places the bench and then calls [request]). */
    fun putAway() { if (open) { CfLog.i("WINDOW close why=recall"); closeFn?.invoke() } }

    fun close() { runCatching { closeFn?.invoke() } }

    fun onPanelLifecycle(event: String) {
        panelState = event
        if (event == "ON_RESUME") everOpened = true
        CfLog.i("WINDOW lifecycle $event")
        if (reopenPending && event == "ON_DESTROY") { reopenPending = false; request("reopen") }
    }

    /** Every Stage frame: the head pitch in degrees (null when no valid head). */
    fun stepFrame(pitchDeg: Float?) {
        val now = SystemClock.elapsedRealtime()
        lastStepMs = now
        if (!gate.waiting) { if (levelHint) levelHint = false; return }
        val hint = gate.hintWanted(pitchDeg)
        if (hint != levelHint) levelHint = hint
        gate.step(now, pitchDeg)?.let { why -> levelHint = false; openNow(why, pitchDeg) }
    }

    private fun openNow(why: String, pitch: Float?) {
        main.removeCallbacks(watchdog)
        openWhy = why; openSeq++
        CfLog.i("WINDOW open why=$why pitch=$pitch")
        openFn?.invoke()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!gate.waiting) return
            val now = SystemClock.elapsedRealtime()
            val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > 15_000 else now - lastStepMs > 3000
            if (stalled) { val why = gate.pending!!; gate.cancel(); levelHint = false; openNow("$why/noframes", null) } else main.postDelayed(this, 3000)
        }
    }
}
