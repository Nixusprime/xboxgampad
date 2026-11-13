package com.xboxpad

import android.content.pm.PackageManager
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

/**
 * Creates a real virtual Xbox 360 controller (a uinput device) by running Android's
 * built-in `uinput -` command with shell privileges through Shizuku, then feeds it
 * button/axis events. Games and emulators see it as a normal USB Xbox 360 pad.
 */
object GamepadBackend {

    enum class State { OFF, STARTING, READY, ERROR }

    @Volatile
    var state: State = State.OFF
        private set

    @Volatile
    var log: String = ""
        private set

    // Linux evdev codes for the Xbox 360 pad (xpad driver layout)
    const val BTN_A = 304
    const val BTN_B = 305
    const val BTN_X = 307
    const val BTN_Y = 308
    const val BTN_LB = 310
    const val BTN_RB = 311
    const val BTN_BACK = 314
    const val BTN_START = 315
    const val BTN_GUIDE = 316
    const val BTN_L3 = 317
    const val BTN_R3 = 318

    const val ABS_LX = 0
    const val ABS_LY = 1
    const val ABS_LT = 2   // ABS_Z
    const val ABS_RX = 3
    const val ABS_RY = 4
    const val ABS_RT = 5   // ABS_RZ
    const val ABS_HAT_X = 16
    const val ABS_HAT_Y = 17

    private val io = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<String>()

    @Volatile
    private var proc: Process? = null

    @Volatile
    private var out: OutputStream? = null

    private fun appendLog(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 6) logLines.removeFirst()
            log = logLines.joinToString("\n")
        }
    }

    private fun registerJson(): String {
        val keys = intArrayOf(
            BTN_A, BTN_B, BTN_X, BTN_Y, BTN_LB, BTN_RB,
            BTN_BACK, BTN_START, BTN_GUIDE, BTN_L3, BTN_R3
        )
        val axes = intArrayOf(ABS_LX, ABS_LY, ABS_LT, ABS_RX, ABS_RY, ABS_RT, ABS_HAT_X, ABS_HAT_Y)

        fun absInfo(code: Int, min: Int, max: Int, fuzz: Int, flat: Int): String =
            "{\"code\":$code,\"info\":{\"value\":0,\"minimum\":$min,\"maximum\":$max," +
                "\"fuzz\":$fuzz,\"flat\":$flat,\"resolution\":0}}"

        val infos = listOf(
            absInfo(ABS_LX, -32768, 32767, 16, 128),
            absInfo(ABS_LY, -32768, 32767, 16, 128),
            absInfo(ABS_RX, -32768, 32767, 16, 128),
            absInfo(ABS_RY, -32768, 32767, 16, 128),
            absInfo(ABS_LT, 0, 255, 0, 0),
            absInfo(ABS_RT, 0, 255, 0, 0),
            absInfo(ABS_HAT_X, -1, 1, 0, 0),
            absInfo(ABS_HAT_Y, -1, 1, 0, 0)
        )

        // 100 = UI_SET_EVBIT (1 = EV_KEY, 3 = EV_ABS), 101 = UI_SET_KEYBIT, 103 = UI_SET_ABSBIT
        // vid 0x045e / pid 0x028e = Microsoft Xbox 360 controller (Android ships a key layout for it)
        return "{\"id\":1,\"command\":\"register\",\"name\":\"Microsoft X-Box 360 pad\"," +
            "\"vid\":1118,\"pid\":654,\"bus\":\"usb\"," +
            "\"configuration\":[" +
            "{\"type\":100,\"data\":[1,3]}," +
            "{\"type\":101,\"data\":[${keys.joinToString(",")}]}," +
            "{\"type\":103,\"data\":[${axes.joinToString(",")}]}]," +
            "\"abs_info\":[${infos.joinToString(",")}]}"
    }

    private fun rootCause(t: Throwable): Throwable =
        if (t is InvocationTargetException && t.targetException != null) t.targetException else t

    private fun pump(stream: InputStream) {
        val t = Thread {
            try {
                stream.bufferedReader().forEachLine { appendLog(it) }
            } catch (_: Exception) {
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun cleanup() {
        val p = proc
        val o = out
        proc = null
        out = null
        try {
            o?.close()
        } catch (_: Exception) {
        }
        try {
            p?.destroy()
        } catch (_: Exception) {
        }
    }

    fun start() {
        if (state == State.STARTING || state == State.READY) return
        state = State.STARTING
        synchronized(logLines) {
            logLines.clear()
            log = ""
        }
        io.execute {
            try {
                cleanup()
                if (!Shizuku.pingBinder()) {
                    throw IllegalStateException("Shizuku is not running")
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw IllegalStateException("Shizuku permission not granted")
                }
                // Shizuku.newProcess is private in API 13 but is still the supported way to run a shell command.
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                m.isAccessible = true
                val p = m.invoke(null, arrayOf("uinput", "-"), null, null) as Process
                val o = p.outputStream
                proc = p
                out = o
                pump(p.errorStream)
                pump(p.inputStream)

                val waiter = Thread {
                    val code = try {
                        p.waitFor()
                    } catch (_: Exception) {
                        -1
                    }
                    if (proc === p) {
                        appendLog("uinput exited (code $code)")
                        state = State.ERROR
                    }
                }
                waiter.isDaemon = true
                waiter.start()

                o.write((registerJson() + "\n").toByteArray())
                o.flush()

                // Give Android a moment to notice the new input device before sending events.
                Thread.sleep(1500)
                if (state == State.STARTING && proc === p) {
                    state = State.READY
                }
            } catch (t: Throwable) {
                val c = rootCause(t)
                appendLog(c.message ?: c.javaClass.simpleName)
                state = State.ERROR
            }
        }
    }

    fun stop() {
        state = State.OFF
        io.execute { cleanup() }
    }

    private fun send(events: String) {
        if (state != State.READY) return
        io.execute {
            val o = out ?: return@execute
            try {
                o.write(("{\"id\":1,\"command\":\"inject\",\"events\":[$events]}\n").toByteArray())
                o.flush()
            } catch (e: Exception) {
                appendLog("write failed: ${e.message}")
                state = State.ERROR
            }
        }
    }

    /** Press or release a button (EV_KEY) followed by a SYN report. */
    fun key(code: Int, down: Boolean) = send("1,$code,${if (down) 1 else 0},0,0,0")

    /** Move a single absolute axis (EV_ABS) followed by a SYN report. */
    fun axis(code: Int, value: Int) = send("3,$code,$value,0,0,0")

    /** Move two absolute axes in one report (stick X+Y, or D-pad hat X+Y). */
    fun axes2(codeA: Int, valueA: Int, codeB: Int, valueB: Int) =
        send("3,$codeA,$valueA,3,$codeB,$valueB,0,0,0")
}
