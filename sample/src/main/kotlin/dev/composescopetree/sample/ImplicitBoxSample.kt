package dev.composescopetree.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.composescopetree.ImplicitScopeTreeHost
import dev.composescopetree.ImplicitScopeNode
import dev.composescopetree.ui.BoxComponent
import dev.composescopetree.ui.BoxDefinition
import dev.composescopetree.ui.ColumnComponent
import dev.composescopetree.ui.RowComponent
import dev.composescopetree.ui.RowDefinition
import kotlinx.coroutines.runBlocking

@Composable
private fun CustomPanel(label: String, content: @Composable () -> Unit) {
    SideEffect { println("CustomPanel label: $label") }
    content()
}

@Composable
private fun panelLabel(prefix: String): String = "$prefix panel"

@Composable
private fun <T> PanelFooter(value: T, vararg tags: String, footer: @Composable String.(Int) -> Unit) {
    footer("$value (${tags.joinToString()})", tags.size)
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() = runBlocking {
    val host = ImplicitScopeTreeHost()
    val scene = ImageComposeScene(200, 200, coroutineContext = coroutineContext)
    var childRenders = 0
    try {
        scene.setContent {
            host.Content {
                Column {
                    Row(Modifier.width(180.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(100.dp).onSizeChanged { println("Box size: $it") },
                        ) {
                            Box(Modifier.size(12.dp).align(Alignment.BottomEnd))
                            CustomPanel(panelLabel("Original")) {
                                PanelFooter(7, "tag") { count ->
                                    SideEffect { println("Original footer: receiver=$this, count=$count") }
                                }
                                SideEffect { childRenders++ }
                            }
                        }
                        Spacer(
                            Modifier.weight(1f).height(4.dp)
                                .onSizeChanged { println("Weighted spacer: ${it.width}px") }
                        )
                    }
                    LazyColumn(modifier = Modifier.size(40.dp)) {
                        item { Spacer(Modifier.size(8.dp)) }
                    }
                }
            }
        }
        scene.render().close()

        val column = host.roots.single { it.parameters(ColumnComponent) != null }
        val row = column.children.single { it.parameters(RowComponent) != null }
        val box = row.children.single { it.parameters(BoxComponent) != null }
        println("Discovered ${box.site}, with ${box.children.size} children")
        val list = column.children.single { it.function?.endsWith(".LazyColumn") == true }
        println("Discovered ordinary LazyColumn with arguments: ${list.arguments?.names}")
        host.overrideArguments(list.address) {
            it.with("modifier", Modifier.size(60.dp).onSizeChanged { size -> println("Edited LazyColumn size: $size") })
        }
        val panel = box.children.single { it.function?.endsWith(".CustomPanel") == true }
        val label = box.children.single { it.function?.endsWith(".panelLabel") == true }
        host.replaceCall(label.address) { args, _ -> "${args["prefix"]} result override" }
        Snapshot.sendApplyNotifications()
        scene.render().close()

        val footer = descendants(panel).single { it.function?.endsWith(".PanelFooter") == true }
        host.replaceCallWithSlot(footer.address, "footer") { args, content: @Composable String.(Int) -> Unit ->
            val tags: Array<out String> = args.getAs("tags")
            content("replacement receiver", tags.size)
        }
        host.overrideArguments(panel.address) { it.with("label", "Edited panel") }
        host.overrideParameters(box.address, BoxComponent) {
            it.copy(modifier = Modifier.size(60.dp).onSizeChanged { size -> println("Edited size: $size") })
        }
        Snapshot.sendApplyNotifications()
        scene.render().close()

        host.replaceDefinition(panel.address) { content ->
            content()
            SideEffect { println("CustomPanel replacement composed") }
        }
        Snapshot.sendApplyNotifications()
        scene.render().close()

        host.replaceDefinition(box.address, BoxDefinition) { p, content ->
            Box(p.modifier, p.contentAlignment, p.propagateMinConstraints, content)
        }
        Snapshot.sendApplyNotifications()
        scene.render().close()

        host.replaceDefinition(row.address, RowDefinition) { p, content ->
            Row(Modifier.width(160.dp), p.horizontalArrangement, p.verticalAlignment, content)
            SideEffect { println("Replacement Row composed with original RowScope content") }
        }
        Snapshot.sendApplyNotifications()
        scene.render().close()
        println("Original content rendered $childRenders times")
    } finally {
        scene.close()
    }
}

private fun descendants(node: ImplicitScopeNode): Sequence<ImplicitScopeNode> =
    sequenceOf(node) + node.children.asSequence().flatMap(::descendants)
