package dev.composescopetree.sample

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.composescopetree.RenderScopeTree
import dev.composescopetree.ScopeComponent
import dev.composescopetree.ScopeContent
import dev.composescopetree.ScopeNode
import dev.composescopetree.ScopeTreeHost
import dev.composescopetree.ScopeTreeNodeSnapshot
import dev.composescopetree.scopeSite
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TickingFrameClock : MonotonicFrameClock {
    private var time = 0L

    override suspend fun <R> withFrameNanos(
        onFrame: (frameTimeNanos: Long) -> R
    ): R {
        time += 16_000_000L
        return onFrame(time)
    }
}

class UnitApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun remove(index: Int, count: Int) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun onClear() {}
}

private data class PanelParameters(val padding: Int, val title: String)

private val Panel = ScopeComponent<PanelParameters> { parameters, children ->
    SideEffect { println("Panel: padding=${parameters.padding}, title=${parameters.title}") }
    children.Render()
}

fun main() {
    runBlocking(TickingFrameClock()) {
        val recomposer = Recomposer(coroutineContext)
        val recomposeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }

        val host = ScopeTreeHost(recomposer)
        val title = mutableStateOf("Declared title")

        host.setContent {
            ScopeNode(
                site = scopeSite(),
                businessKey = "app",
                component = Panel,
                parameters = PanelParameters(padding = 4, title = title.value),
                children = {
                    ScopeNode(
                        site = scopeSite(),
                        businessKey = "item1",
                        content = ScopeContent(data = "Item 1")
                    )
                    ScopeNode(
                        site = scopeSite(),
                        businessKey = "item2",
                        content = ScopeContent(data = "Item 2", render = {
                            SideEffect { println("Rendering original Item 2") }
                        })
                    )
                }
            )
        }

        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()

        println("=== Initial Tree ===")
        printTree(host.roots)

        val composition = Composition(UnitApplier(), recomposer)
        composition.setContent {
            RenderScopeTree(host.roots)
        }

        val address = host.roots.single().address
        println("\n=== Apply padding override and update declared title ===")
        host.overrideParameters(address, Panel) { it.copy(padding = 24) }
        title.value = "Updated title"
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()

        println("\n=== Replace the panel and keep its children ===")
        host.replaceDefinition(address) { children ->
            SideEffect { println("Replacement panel") }
            children.Render()
        }
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()

        val removed = host.roots.single().children.first().address
        host.removeNode(removed)
        println("\n=== After Removing Item 1 ===")
        printTree(host.roots)
        host.restoreNode(removed)

        host.setVisibility(removed, false)
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()
        println("\n=== After Hiding Item 1 ===")
        printTree(host.roots)

        host.replaceContent(
            host.roots[0].children[1].address,
            ScopeContent(data = "Item 2 Updated")
        )
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()
        println("\n=== After Updating Item 2 ===")
        printTree(host.roots)

        composition.dispose()
        host.close()
        recomposer.cancel()
        recomposeJob.cancelAndJoin()
    }
}

private fun printTree(nodes: List<ScopeTreeNodeSnapshot>, indent: Int = 0) {
    for (node in nodes) {
        val prefix = "  ".repeat(indent)
        println("$prefix- ${node.businessKey} [visible=${node.visible}] data=${node.content.data}")
        printTree(node.children, indent + 1)
    }
}
