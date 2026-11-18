package dev.composescopetree.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.composescopetree.ImplicitScopeNode
import dev.composescopetree.ImplicitScopeTreeHost
import dev.composescopetree.ui.BoxDefinition
import dev.composescopetree.ui.RowDefinition
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ImplicitScopeBehaviorTest {
    @Test
    fun filteredParentsStillRenderAndExposeSelectedDescendants() {
        val host = ImplicitScopeTreeHost { site, name ->
            "ImplicitScopeBehaviorTest.kt:" in site && name.endsWith(".ContentWrapper")
        }
        withScene(host) { scene, tree ->
            val rendered = mutableListOf<String>()
            scene.setContent {
                tree.Content {
                    Row {
                        Box { ContentWrapper(label = "nested", report = rendered::add) {} }
                    }
                }
            }
            scene.render()
            assertEquals(listOf("nested"), rendered)
            val node = tree.roots.single()
            assertTrue(node.function?.endsWith(".ContentWrapper") == true)
            assertEquals("nested", node.arguments?.get("label"))
        }
    }

    @Test
    fun untrackedCallsEvaluateArgumentsOnceAndCanContainAnImplicitTree() = withScene { scene, host ->
        val arguments = mutableListOf<String>()
        val rendered = mutableListOf<String>()
        fun supplied(label: String): String = label.also { arguments.add(it) }
        fun report(): (String) -> Unit {
            arguments.add("report")
            return { rendered.add(it) }
        }
        scene.setContent {
            ContentWrapper(report = report(), label = supplied("outside")) {
                host.Content {
                    ContentWrapper(label = supplied("inside"), report = rendered::add) {}
                }
            }
        }
        scene.render()
        assertEquals(listOf("report", "outside", "inside"), arguments)
        assertEquals(listOf("outside", "inside"), rendered)
        val node = host.nodes("ContentWrapper").single()
        assertEquals("inside", node.arguments?.get("label"))
        host.replaceDefinition(node.address) { content ->
            content()
            SideEffect { rendered.add("replacement") }
        }
        scene.render()
        assertEquals(listOf("outside", "inside", "replacement"), rendered)
    }

    @Test
    fun keyedReorderKeepsEditsAndSourceRemovalCleansThem() = withScene { scene, host ->
        val keys = mutableStateOf(listOf(1, 2, 3))
        val rendered = mutableMapOf<Int, String>()
        val record = { id: Int, label: String -> rendered[id] = label }
        val remove = { id: Int -> rendered.remove(id); Unit }
        scene.setContent {
            host.Content {
                for (id in keys.value) {
                    key(id) { LifecycleLeaf(id, "source-$id", record, remove) }
                }
            }
        }
        scene.render()
        fun address(id: Int) = host.nodes("LifecycleLeaf").single { it.arguments?.get("id") == id }.address
        val original = address(2)
        host.overrideArguments(original) { it.with("label", "edited") }
        keys.value = listOf(3, 2, 1)
        scene.render()
        assertEquals(original, address(2))
        assertEquals("edited", rendered[2])

        var unrelatedApplications = 0
        host.overrideArguments(address(1)) { args ->
            unrelatedApplications++
            args
        }
        scene.render()
        unrelatedApplications = 0
        host.overrideArguments(original) { it.with("label", "edited again") }
        scene.render()
        assertEquals(0, unrelatedApplications)

        host.removeNode(original)
        scene.render()
        assertFalse(rendered.containsKey(2))
        assertEquals(2, host.nodes("LifecycleLeaf").size)
        host.restoreNode(original)
        scene.render()
        assertEquals("edited again", rendered[2])

        keys.value = listOf(1, 3)
        scene.render()
        assertThrows(IllegalArgumentException::class.java) { host.restoreNode(original) }
        keys.value = listOf(1, 2, 3)
        scene.render()
        assertNotEquals(original, address(2))
        assertEquals("source-2", rendered[2])
    }

    @Test
    fun typedReplacementUsesNewRowAndCanBeReset() = withScene { scene, host ->
        var measured = 0
        scene.setContent {
            host.Content {
                Row(Modifier.width(120.dp)) {
                    Box(Modifier.size(20.dp))
                    Spacer(Modifier.weight(1f).height(10.dp).onSizeChanged { measured = it.width })
                }
            }
        }
        scene.render()
        assertEquals(100, measured)
        val row = host.nodes("Row").single().address
        assertThrows(IllegalArgumentException::class.java) { host.replaceDefinition(row, BoxDefinition) { _, _ -> } }
        host.replaceDefinition(row, RowDefinition) { p, content ->
            Row(Modifier.width(160.dp), p.horizontalArrangement, p.verticalAlignment, content)
        }
        scene.render()
        assertEquals(140, measured)
        host.resetDefinition(row)
        scene.render()
        assertEquals(100, measured)
    }

    @Test
    fun genericSlotsDefaultsAndReturnOverridesKeepTheirTypes() = withScene { scene, host ->
        val suffix = mutableStateOf("local")
        val rendered = mutableListOf<String>()
        scene.setContent {
            host.Content {
                CompositionLocalProvider(LocalDefault provides suffix.value) {
                    MultipleSlots(
                        computeLabel(5),
                        *arrayOf("tag", "second"),
                        header = { count -> SideEffect { rendered.add("header=$this/$count") } },
                        footer = { SideEffect { rendered.add("footer") } },
                    )
                    EarlyExit {
                        SideEffect { rendered.add("local lambda return") }
                        return@EarlyExit
                    }
                }
            }
        }
        scene.render()
        assertTrue("header=5:local/2" in rendered)
        assertTrue("local lambda return" in rendered)

        val label = host.nodes("computeLabel").single().address
        host.replaceCall(label) { arguments, original -> "${original()}/${arguments["value"]}" }
        suffix.value = "updated"
        scene.render()
        assertTrue("header=5/5:updated/2" in rendered)

        val call = host.nodes("MultipleSlots").single().address
        assertThrows(IllegalArgumentException::class.java) { host.replaceDefinition(call) { } }
        host.replaceCall(call) { arguments, _ ->
            val header: @Composable String.(Int) -> Unit = arguments.getAs("header")
            val footer: @Composable () -> Unit = arguments.getAs("footer")
            val tags: Array<out String> = arguments.getAs("tags")
            header("replacement", tags.size)
            footer()
        }
        rendered.clear()
        scene.render()
        assertEquals(listOf("header=replacement/2", "footer"), rendered)

        host.replaceCallWithSlot(call, "header") { arguments, header: @Composable String.(Int) -> Unit ->
            val tags: Array<out String> = arguments.getAs("tags")
            header("typed slot", tags.size)
        }
        rendered.clear()
        scene.render()
        assertEquals(listOf("header=typed slot/2"), rendered)
    }

    @Test
    fun lazyRowsRegisterOnlyWhenComposedAndDisposeOnSceneClose() = withScene { scene, host ->
        val scroll = LazyListState()
        scene.setContent {
            host.Content {
                LazyColumn(Modifier.height(40.dp), state = scroll) {
                    items((0..99).toList(), key = { it }) { id -> LazyLeaf(id) }
                }
            }
        }
        scene.render()
        fun mountedIds() = host.nodes("LazyLeaf").map { it.arguments?.get("id") }
        assertTrue(0 in mountedIds())
        assertFalse(60 in mountedIds())
        scroll.requestScrollToItem(60)
        scene.render()
        assertTrue(60 in mountedIds())
        scene.close()
        assertTrue(host.roots.isEmpty())
    }
}

@Composable
private fun ContentWrapper(label: String, report: (String) -> Unit, content: @Composable () -> Unit) {
    SideEffect { report(label) }
    content()
}

@Composable
private fun LifecycleLeaf(id: Int, label: String, record: (Int, String) -> Unit, remove: (Int) -> Unit) {
    SideEffect { record(id, label) }
    DisposableEffect(id) { onDispose { remove(id) } }
}

private val LocalDefault = compositionLocalOf { "default" }

@Composable
private fun computeLabel(value: Int): String = value.toString()

@Composable
private fun MultipleSlots(
    label: String,
    vararg tags: String,
    suffix: String = LocalDefault.current,
    header: @Composable String.(Int) -> Unit,
    footer: @Composable () -> Unit,
) {
    header("$label:$suffix", tags.size)
    footer()
}

@Composable
private inline fun EarlyExit(content: @Composable () -> Unit) { content() }

@Composable
private fun LazyLeaf(id: Int) {
    Box(Modifier.height((20 + id % 2).dp))
}

private fun ImplicitScopeTreeHost.nodes(name: String): List<ImplicitScopeNode> {
    fun descendants(node: ImplicitScopeNode): Sequence<ImplicitScopeNode> =
        sequenceOf(node) + node.children.asSequence().flatMap(::descendants)
    return roots.asSequence().flatMap(::descendants).filter { it.function?.substringAfterLast('.') == name }.toList()
}

@OptIn(ExperimentalComposeUiApi::class)
private class TestScene(private val scene: ImageComposeScene, private val failure: AtomicReference<Throwable?>) {
    private var closed = false

    fun setContent(content: @Composable () -> Unit) = scene.setContent(content)

    fun render() {
        repeat(2) {
            Snapshot.sendApplyNotifications()
            scene.render().close()
            failure.get()?.let { throw AssertionError("Composition failed", it) }
        }
    }

    fun close() {
        if (!closed) {
            closed = true
            scene.close()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun withScene(
    host: ImplicitScopeTreeHost = ImplicitScopeTreeHost(),
    block: (TestScene, ImplicitScopeTreeHost) -> Unit,
): Unit = runBlocking {
    val failure = AtomicReference<Throwable?>()
    val handler = CoroutineExceptionHandler { _, error -> failure.compareAndSet(null, error) }
    val scene = TestScene(ImageComposeScene(200, 200, coroutineContext = coroutineContext + handler), failure)
    try {
        block(scene, host)
    } finally {
        scene.close()
    }
}
