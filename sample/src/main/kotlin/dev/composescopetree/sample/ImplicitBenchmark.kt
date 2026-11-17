package dev.composescopetree.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.composescopetree.ImplicitScopeTreeHost
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import com.sun.management.ThreadMXBean
import kotlin.math.ceil
import kotlin.system.measureNanoTime

private const val Cells = 1_000
private const val Warmup = 35
private const val Samples = 60

@Composable
private fun BenchmarkCell(id: Int, value: Int, record: (Int, Int) -> Unit) {
    SideEffect { record(id, value) }
}

@Composable
private fun BenchmarkCells(value: Int, record: (Int, Int) -> Unit) {
    repeat(Cells) { id -> key(id) { BenchmarkCell(id, value + id, record) } }
}

fun main(args: Array<String>) = runBlocking(TickingFrameClock()) {
    val tracked = when (args.singleOrNull()) {
        "tracked" -> true
        "plain" -> false
        else -> error("Pass plain or tracked")
    }
    val recomposer = Recomposer(coroutineContext)
    val recomposition = launch(start = CoroutineStart.UNDISPATCHED) {
        recomposer.runRecomposeAndApplyChanges()
    }
    val host = ImplicitScopeTreeHost()
    val state = mutableIntStateOf(0)
    val values = IntArray(Cells)
    var applications = 0
    val record: (Int, Int) -> Unit = { index, value ->
        values[index] = value
        applications++
    }
    val composition = Composition(UnitApplier(), recomposer)
    val allocations = (ManagementFactory.getThreadMXBean() as? ThreadMXBean)?.takeIf {
        it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled
    }
    try {
        val mount = measureNanoTime {
            composition.setContent {
                if (tracked) {
                    host.Content { BenchmarkCells(state.intValue, record) }
                } else {
                    BenchmarkCells(state.intValue, record)
                }
            }
        }
        suspend fun flush() {
            Snapshot.sendApplyNotifications()
            recomposer.awaitIdle()
        }
        val fullUpdates = LongArray(Samples)
        val fullUpdateBytes = LongArray(Samples)
        repeat(Warmup + Samples) { iteration ->
            val before = allocations?.currentThreadAllocatedBytes ?: 0L
            val elapsed = measureNanoTime {
                state.intValue++
                flush()
            }
            if (iteration >= Warmup) {
                fullUpdates[iteration - Warmup] = elapsed
                fullUpdateBytes[iteration - Warmup] = (allocations?.currentThreadAllocatedBytes ?: 0L) - before
            }
        }
        check(values.last() == state.intValue + Cells - 1)
        println("mode=${args.single()} leaves=$Cells mount=${milliseconds(mount)} ms")
        println("full update ${summary(fullUpdates)}")
        if (allocations != null) {
            println("full update median allocation=${fullUpdateBytes.sorted()[Samples / 2] / 1024} KiB")
        }
        if (tracked) {
            val address = host.roots.single().children[Cells / 2].address
            val edits = LongArray(Samples)
            applications = 0
            repeat(Warmup + Samples) { iteration ->
                val elapsed = measureNanoTime {
                    host.overrideArguments(address) { it.with("value", -iteration - 1) }
                    flush()
                }
                if (iteration >= Warmup) edits[iteration - Warmup] = elapsed
            }
            check(values[Cells / 2] == -Warmup - Samples)
            println("one-node edit ${summary(edits)}, leaf applications=$applications/${Warmup + Samples} edits")
            val treeReads = LongArray(Samples) {
                measureNanoTime { check(host.roots.single().children.size == Cells) }
            }
            println("snapshot read ${summary(treeReads)}")
        }
    } finally {
        composition.dispose()
        recomposer.cancel()
        recomposition.cancelAndJoin()
    }
}

private fun summary(times: LongArray): String {
    val sorted = times.sorted()
    return "p50=${milliseconds(sorted[sorted.size / 2])} ms p95=${milliseconds(sorted[ceil(sorted.size * 0.95).toInt() - 1])} ms"
}

private fun milliseconds(nanos: Long): String = String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0)
