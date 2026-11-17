package dev.composescopetree.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.composescopetree.AddressSegment
import dev.composescopetree.RenderScopeTree
import dev.composescopetree.ScopeContent
import dev.composescopetree.ScopeNode
import dev.composescopetree.ScopeTreeHost
import dev.composescopetree.scopeSite
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ScopeTreeBehaviorTest {

    @Test
    fun distinctMarkerCallSites(): Unit = runBlocking(TickingFrameClock()) {
        val recomposer = Recomposer(coroutineContext)
        val recomposeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }

        val host = ScopeTreeHost(recomposer)
        val siteA = scopeSite()
        val siteB = scopeSite()

        assertNotEquals("Distinct marker call sites should differ", siteA, siteB)

        host.close()
        recomposer.cancel()
        recomposeJob.cancelAndJoin()
    }

    @Test
    fun nestingAndSiblings(): Unit = runBlocking(TickingFrameClock()) {
        val recomposer = Recomposer(coroutineContext)
        val recomposeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }

        val host = ScopeTreeHost(recomposer)
        host.setContent {
            ScopeNode(
                site = "root",
                businessKey = "parent",
                content = ScopeContent(data = "parent data"),
                children = {
                    ScopeNode(
                        site = "child",
                        businessKey = "child1",
                        content = ScopeContent(data = "child1 data")
                    )
                    ScopeNode(
                        site = "child",
                        businessKey = "child2",
                        content = ScopeContent(data = "child2 data")
                    )
                }
            )
            ScopeNode(site = "other-root", businessKey = "parent")
        }

        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()

        assertEquals(2, host.roots.size)
        assertEquals(listOf(AddressSegment("root", "parent")), host.roots[0].address.segments)
        assertNotEquals(host.roots[0].address, host.roots[1].address)
        assertEquals(2, host.roots[0].children.size)
        assertEquals(host.roots[0].address, host.roots[0].children[0].address.parent())
        assertEquals("child1", host.roots[0].children[0].businessKey)
        assertEquals("child2", host.roots[0].children[1].businessKey)
        assertNotEquals(
            "Siblings should have different addresses",
            host.roots[0].children[0].address,
            host.roots[0].children[1].address
        )

        host.close()
        recomposer.cancel()
        recomposeJob.cancelAndJoin()
    }

    @Test
    fun reorderAndDataUpdatesRetainOverrides(): Unit = runBlocking(TickingFrameClock()) {
        val recomposer = Recomposer(coroutineContext)
        val recomposeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }

        val host = ScopeTreeHost(recomposer)
        val items = mutableStateOf(listOf("A", "B", "C"))
        val revision = mutableStateOf(1)

        host.setContent {
            ScopeNode(
                site = "root",
                businessKey = "list",
                content = ScopeContent(),
                children = {
                    for (item in items.value) {
                        ScopeNode(
                            site = scopeSite(),
                            businessKey = item,
                            content = ScopeContent(data = "Data $item / ${revision.value}")
                        )
                    }
                }
            )
        }

        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()

        val itemAAddress = host.roots[0].children[0].address
        host.replaceContent(itemAAddress, ScopeContent(data = "Overridden A"))
        host.setVisibility(itemAAddress, false)
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()

        assertEquals("Overridden A", host.roots[0].children[0].content.data)

        items.value = listOf("C", "A", "B")
        revision.value = 2
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()

        assertEquals(3, host.roots[0].children.size)
        assertEquals("C", host.roots[0].children[0].businessKey)
        assertEquals("A", host.roots[0].children[1].businessKey)
        assertEquals("B", host.roots[0].children[2].businessKey)
        assertEquals(itemAAddress, host.roots[0].children[1].address)
        assertFalse(host.roots[0].children[1].visible)
        assertEquals("Data C / 2", host.roots[0].children[0].content.data)
        assertEquals(
            "Override should be retained after reorder",
            "Overridden A",
            host.roots[0].children[1].content.data
        )
        host.resetNode(itemAAddress)
        assertEquals("Data A / 2", host.roots[0].children[1].content.data)
        assertTrue(host.roots[0].children[1].visible)

        host.close()
        recomposer.cancel()
        recomposeJob.cancelAndJoin()
    }

    @Test
    fun resetNodeClearsOverride(): Unit = runBlocking(TickingFrameClock()) {
        val recomposer = Recomposer(coroutineContext)
        val recomposeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }

        val host = ScopeTreeHost(recomposer)
        host.setContent {
            ScopeNode(
                site = "root",
                businessKey = "node",
                content = ScopeContent(data = "Declared Data")
            )
        }

        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()

        val address = host.roots[0].address
        host.replaceContent(address, ScopeContent(data = "Overridden Data"))
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()
        assertEquals("Overridden Data", host.roots[0].content.data)

        host.resetNode(address)
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()
        assertEquals("Declared Data", host.roots[0].content.data)

        host.close()
        recomposer.cancel()
        recomposeJob.cancelAndJoin()
    }

    @Test
    fun removeAndReinsertClearsOverride(): Unit = runBlocking(TickingFrameClock()) {
        val recomposer = Recomposer(coroutineContext)
        val recomposeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }

        val host = ScopeTreeHost(recomposer)
        val showChild = mutableStateOf(true)

        host.setContent {
            ScopeNode(
                site = "root",
                businessKey = "parent",
                content = ScopeContent(),
                children = {
                    if (showChild.value) {
                        ScopeNode(
                            site = "child",
                            businessKey = "child",
                            content = ScopeContent(data = "Declared Child")
                        )
                    }
                }
            )
        }

        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()

        val childAddress = host.roots[0].children[0].address
        host.replaceContent(childAddress, ScopeContent(data = "Overridden Child"))
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()
        assertEquals("Overridden Child", host.roots[0].children[0].content.data)

        showChild.value = false
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()
        assertEquals(0, host.roots[0].children.size)

        showChild.value = true
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()
        assertEquals(1, host.roots[0].children.size)
        assertEquals(
            "Reinserted node should have declared content",
            "Declared Child",
            host.roots[0].children[0].content.data
        )

        host.close()
        recomposer.cancel()
        recomposeJob.cancelAndJoin()
    }

    @Test
    fun visibilityAndProgrammaticRendererExecution(): Unit = runBlocking(TickingFrameClock()) {
        val recomposer = Recomposer(coroutineContext)
        val recomposeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }

        val host = ScopeTreeHost(recomposer)
        val rendered = mutableListOf<String>()
        host.setContent {
            ScopeNode(
                site = "root",
                businessKey = "visible",
                content = ScopeContent(data = "Visible Node", render = {
                    SideEffect { rendered.add("declared") }
                })
            )
        }

        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()

        assertTrue(host.roots[0].visible)
        host.setVisibility(host.roots[0].address, false)
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()
        assertFalse(host.roots[0].visible)

        val composition = Composition(UnitApplier(), recomposer)
        composition.setContent {
            RenderScopeTree(host.roots)
        }

        Snapshot.sendApplyNotifications()
        withTimeout(5.seconds) {
            recomposer.awaitIdle()
        }
        assertTrue(rendered.isEmpty())

        val address = host.roots.single().address
        host.replaceContent(address, ScopeContent(render = {
            SideEffect { rendered.add("replacement") }
        }))
        host.setVisibility(address, true)
        Snapshot.sendApplyNotifications()
        withTimeout(5.seconds) { recomposer.awaitIdle() }
        assertEquals("replacement", rendered.last())

        host.resetNode(address)
        Snapshot.sendApplyNotifications()
        withTimeout(5.seconds) { recomposer.awaitIdle() }
        assertEquals("declared", rendered.last())

        composition.dispose()
        host.close()
        recomposer.cancel()
        recomposeJob.cancelAndJoin()
    }

    @Test
    fun siblingsCanShareASiteWithDifferentBusinessKeys(): Unit = runBlocking(TickingFrameClock()) {
        val recomposer = Recomposer(coroutineContext)
        val recomposeJob = launch(start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }

        val host = ScopeTreeHost(recomposer)
        val sharedSite = scopeSite()

        host.setContent {
            ScopeNode(
                site = "root",
                businessKey = "parent",
                content = ScopeContent(),
                children = {
                    ScopeNode(
                        site = sharedSite,
                        businessKey = "dup1",
                        content = ScopeContent(data = "First")
                    )
                    ScopeNode(
                        site = sharedSite,
                        businessKey = "dup2",
                        content = ScopeContent(data = "Second")
                    )
                }
            )
        }

        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()

        assertEquals(2, host.roots[0].children.size)
        assertEquals("dup1", host.roots[0].children[0].businessKey)
        assertEquals("dup2", host.roots[0].children[1].businessKey)
        assertNotEquals(
            "Siblings with different business keys should have unique addresses",
            host.roots[0].children[0].address,
            host.roots[0].children[1].address
        )

        host.close()
        recomposer.cancel()
        recomposeJob.cancelAndJoin()
    }

    @Test
    fun duplicateSiblingsAreRejectedAtAnyDepth(): Unit = runBlocking(TickingFrameClock()) {
        val recomposer = Recomposer(coroutineContext)
        val site = scopeSite()
        val duplicateNodes: @Composable () -> Unit = {
            repeat(2) { ScopeNode(site, "duplicate") }
        }

        try {
            ScopeTreeHost(recomposer).use { host ->
                val failure = assertThrows(IllegalStateException::class.java) {
                    host.setContent(duplicateNodes)
                }
                assertTrue(failure.message.orEmpty().contains("Duplicate scope node"))
            }
            ScopeTreeHost(recomposer).use { host ->
                val failure = assertThrows(IllegalStateException::class.java) {
                    host.setContent { ScopeNode("parent", "parent", children = duplicateNodes) }
                }
                assertTrue(failure.message.orEmpty().contains("Duplicate scope node"))
            }
        } finally {
            recomposer.cancel()
        }
    }
}
