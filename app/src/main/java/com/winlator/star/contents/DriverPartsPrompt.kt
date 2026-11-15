package com.winlator.star.contents

import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Asks which parts of a driver zip that carries several (DroidDeck's DD-Turnip: an Android and a Linux driver) to
 * install. The install threads call [ask]; the app's window shows the question ([com.winlator.star.ui.DriverPartsPromptHost])
 * and answers it. With no window to ask in, or on the main thread, the answer is [defaultParts] without asking.
 */
object DriverPartsPrompt {
    class Request(val name: String, val parts: List<DriverInstaller.Place>, internal val reply: CompletableFuture<Set<DriverInstaller.Place>?>)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request
    private val hosts = AtomicInteger(0)
    private val lock = Any()

    /** Android when the zip has it, else its first part. */
    fun defaultParts(parts: List<DriverInstaller.Place>): Set<DriverInstaller.Place> =
        if (DriverInstaller.Place.ANDROID in parts) setOf(DriverInstaller.Place.ANDROID) else parts.take(1).toSet()

    fun hostShown() { hosts.incrementAndGet() }
    fun hostGone() { hosts.decrementAndGet() }

    /** The parts to install, or null when the user cancelled. Blocks until answered; one question at a time. */
    fun ask(name: String, parts: List<DriverInstaller.Place>): Set<DriverInstaller.Place>? {
        if (parts.size < 2) return parts.toSet()
        if (Looper.myLooper() == Looper.getMainLooper() || hosts.get() <= 0) return defaultParts(parts)
        synchronized(lock) {
            val reply = CompletableFuture<Set<DriverInstaller.Place>?>()
            val req = Request(name, parts, reply)
            _request.value = req
            try {
                // A window that goes away for good (not a rotation) leaves the default.
                var noHostSeconds = 0
                while (true) {
                    try {
                        return reply.get(1, TimeUnit.SECONDS)
                    } catch (_: TimeoutException) {
                        noHostSeconds = if (hosts.get() <= 0) noHostSeconds + 1 else 0
                        if (noHostSeconds >= 10) return defaultParts(parts)
                    }
                }
            } finally {
                _request.compareAndSet(req, null)
            }
        }
    }

    /** The window's answer: the parts ticked, or null for Cancel. */
    fun answer(req: Request, parts: Set<DriverInstaller.Place>?) {
        req.reply.complete(parts)
    }
}
