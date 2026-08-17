package com.winlator.star.perf

import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Non-root thread-priority boost for the "Thread Priority Boost" toggle.
 *
 * WHY THE FIRST VERSION DID NOTHING (device finding): the app's own render/present threads in this
 * process already run at nice −10…−20 (Android's top-app scheduling + the app already prioritizes
 * them). Boosting them to THREAD_PRIORITY_URGENT_DISPLAY (−8) is a HIGHER nice / LOWER priority — it
 * either no-ops or DOWNGRADES an already-hot thread. So this version:
 *
 *  1. NEVER DOWNGRADES — it reads each thread's current nice ([Process.getThreadPriority]) and only
 *     touches threads that are BELOW the target (a strict upgrade). Already-hot threads are left alone.
 *  2. Targets the threads that actually have headroom: the GUEST CPU workers (the box64/wine/proot
 *     subtree under [android.os.Process] `guestRootPid`), which run at default nice 0 — that's where a
 *     measurable, useful boost lands. Plus our own audio/worker threads (the render thread is already
 *     maxed, so never-downgrade skips it).
 *  3. Is MEASURABLE + REVERSIBLE — it snapshots each thread's original nice and logs before→after, and
 *     [restore] writes the captured nice back exactly.
 *
 * All same-uid renices (the guest tree is our child processes); no root, no CAP_SYS_NICE. Anything the
 * rlimit rejects is caught and skipped (weaker ladder rung tried first).
 */
object PerfPriority {

    private const val TAG = "PerfPriority"

    // Strongest→weakest boost targets. We apply the strongest rung that (a) is a strict upgrade over
    // the thread's current nice and (b) the rlimit actually allows.
    private val BOOST_LADDER = intArrayOf(
        Process.THREAD_PRIORITY_URGENT_DISPLAY, // -8
        Process.THREAD_PRIORITY_DISPLAY,        // -4
        Process.THREAD_PRIORITY_FOREGROUND,     // -2
    )

    // Our OWN threads worth boosting (the render/present ones are already hotter than any rung, so
    // never-downgrade skips them; audio/worker pumps may have headroom). Lower-cased substring match.
    private val APP_THREAD_TOKENS = arrayOf("audio", "worker", "render", "present", "vk", "gl")

    // tid -> original nice, captured on first touch, for exact restore.
    private val originalNice = ConcurrentHashMap<Int, Int>()

    /**
     * Boost the guest CPU-worker subtree (under [guestRootPid], = GuestProgramLauncherComponent.getPid)
     * plus our own audio/worker threads. Never downgrades. Returns the number of threads actually moved.
     */
    fun boost(guestRootPid: Int): Int {
        var count = 0
        for (tid in collectAppThreadTids()) if (boostTid(tid)) count++
        if (guestRootPid > 0) for (tid in collectGuestTids(guestRootPid)) if (boostTid(tid)) count++
        Log.d(TAG, "priority boost: moved $count thread(s) (guestRootPid=$guestRootPid)")
        return count
    }

    /** Restore every thread we boosted to its captured original nice. */
    fun restore(): Int {
        var count = 0
        for ((tid, nice) in originalNice) {
            try { Process.setThreadPriority(tid, nice); count++ } catch (_: Throwable) {}
        }
        Log.d(TAG, "priority boost: restored $count thread(s)")
        originalNice.clear()
        return count
    }

    /**
     * Raise one thread to the strongest ladder rung that is a STRICT upgrade and that the rlimit
     * accepts. Lower nice = higher priority, so "upgrade" means target < current. Returns whether the
     * thread moved. Snapshots the original nice once so [restore] is exact.
     */
    private fun boostTid(tid: Int): Boolean {
        val cur = try { Process.getThreadPriority(tid) } catch (_: Throwable) { return false }
        for (target in BOOST_LADDER) {
            if (target >= cur) continue // not a strict upgrade — never downgrade
            try {
                if (!originalNice.containsKey(tid)) originalNice[tid] = cur
                Process.setThreadPriority(tid, target)
                val after = try { Process.getThreadPriority(tid) } catch (_: Throwable) { target }
                if (after < cur) {
                    Log.d(TAG, "tid=$tid nice $cur -> $after")
                    return true
                }
                // No move (rlimit clamped it back) — drop the snapshot if we didn't actually change it.
                if (after == cur) originalNice.remove(tid)
            } catch (_: Throwable) {
                originalNice.remove(tid)
                // try the next weaker rung
            }
        }
        return false
    }

    /** Our own process threads whose comm matches a worthwhile token. */
    private fun collectAppThreadTids(): List<Int> {
        val tasks = File("/proc/self/task").listFiles() ?: return emptyList()
        val out = ArrayList<Int>()
        for (task in tasks) {
            val tid = task.name.toIntOrNull() ?: continue
            val comm = try { File(task, "comm").readText().trim().lowercase() } catch (_: Exception) { continue }
            if (APP_THREAD_TOKENS.any { comm.contains(it) }) out.add(tid)
        }
        return out
    }

    /**
     * Every thread of every process in the guest process subtree rooted at [rootPid] — box64, wine,
     * wineserver and the proot children (all our own uid). Built from the /proc ppid graph.
     */
    private fun collectGuestTids(rootPid: Int): List<Int> {
        val procDirs = File("/proc").listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }
            ?: return emptyList()
        val ppidOf = HashMap<Int, Int>()
        val pids = ArrayList<Int>(procDirs.size)
        for (d in procDirs) {
            val pid = d.name.toIntOrNull() ?: continue
            val stat = try { File(d, "stat").readText() } catch (_: Exception) { continue }
            // comm (2nd field) can contain spaces/parens, so parse fields after the last ')'.
            val rp = stat.lastIndexOf(')')
            if (rp < 0 || rp + 2 >= stat.length) continue
            val after = stat.substring(rp + 2).trim().split(" ")
            val ppid = after.getOrNull(1)?.toIntOrNull() ?: continue // state, ppid, ...
            ppidOf[pid] = ppid
            pids.add(pid)
        }
        val subtree = HashSet<Int>()
        subtree.add(rootPid)
        var changed = true
        while (changed) {
            changed = false
            for (pid in pids) {
                if (pid in subtree) continue
                val pp = ppidOf[pid]
                if (pp != null && pp in subtree) { subtree.add(pid); changed = true }
            }
        }
        val tids = ArrayList<Int>()
        for (pid in subtree) {
            File("/proc/$pid/task").listFiles()?.forEach { t -> t.name.toIntOrNull()?.let { tids.add(it) } }
        }
        return tids
    }
}
