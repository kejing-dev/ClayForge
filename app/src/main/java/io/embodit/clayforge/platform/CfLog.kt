package io.embodit.clayforge.platform

import android.util.Log

/**
 * One tag for the whole app; QA greps `ClayForge`. Lines are mirrored to files/input-log.txt (PICO OS 6.2 headsets can
 * drop app logcat lines, board-input template): `adb shell run-as io.embodit.clayforge cat files/input-log.txt`.
 */
object CfLog {
    const val TAG = "ClayForge"
    private var file: java.io.File? = null
    private const val MAX_BYTES = 512 * 1024
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    fun attach(context: android.content.Context) { file = java.io.File(context.filesDir, "input-log.txt") }

    @Synchronized
    fun i(msg: String) {
        Log.i(TAG, msg)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.renameTo(java.io.File(f.parentFile, "input-log.old.txt"))
            f.appendText("${stamp.format(java.util.Date())} $msg\n")
        }
    }
    fun w(msg: String) { Log.w(TAG, msg); i("WARN $msg") }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t); i("ERROR $msg ${t ?: ""}") }
}
