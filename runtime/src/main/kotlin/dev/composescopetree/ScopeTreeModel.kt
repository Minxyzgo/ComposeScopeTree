package dev.composescopetree

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

public fun scopeSite(): String =
    error("scopeSite() was not rewritten by the ComposeScopeTree K2 IR plugin. Ensure the plugin is applied to this module.")

public data class AddressSegment(public val site: String, public val businessKey: String)

public class ScopeAddress(segments: List<AddressSegment>) {
    public val segments: List<AddressSegment> = readOnlyList(segments)

    public fun parent(): ScopeAddress? =
        if (segments.isEmpty()) null else ScopeAddress(segments.subList(0, segments.size - 1))

    override fun equals(other: Any?): Boolean = other is ScopeAddress && other.segments == segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = "ScopeAddress($segments)"
}

public class ScopeContent(
    public val data: Any? = null,
    public val render: @Composable () -> Unit = {},
)

public class ScopeTreeNodeSnapshot internal constructor(
    public val address: ScopeAddress,
    public val businessKey: String,
    public val content: ScopeContent,
    public val visible: Boolean,
    public val children: List<ScopeTreeNodeSnapshot>,
    private val component: BoundComponent<*>?,
    private val definition: ScopeRenderer?,
    private val contentReplaced: Boolean,
) {
    public fun <P : Any> parameters(component: ScopeComponent<P>): P? =
        this.component?.parametersFor(component)

    @Composable
    public fun Render(children: ScopeChildren) {
        when {
            definition != null -> definition.invoke(children)
            component != null && !contentReplaced -> component.Render(children)
            else -> {
                content.render()
                children.Render()
            }
        }
    }
}

private class LiveNode(
    val site: String,
    val businessKey: String,
    var declaredContent: ScopeContent,
    var component: BoundComponent<*>? = null,
) {
    val segment = AddressSegment(site, businessKey)
    val children: MutableList<LiveNode> = ArrayList()
}

private data class ScopeOverride(
    val content: ScopeContent? = null,
    val definition: ScopeRenderer? = null,
    val parameters: ((BoundComponent<*>) -> BoundComponent<*>)? = null,
    val visible: Boolean = true,
    val removed: Boolean = false,
)

private class NodeApplier(
    root: LiveNode,
    private val publish: () -> Unit,
) : AbstractApplier<LiveNode>(root) {
    override fun insertTopDown(index: Int, instance: LiveNode) {
        current.children.add(index, instance)
    }

    override fun insertBottomUp(index: Int, instance: LiveNode) { }

    override fun remove(index: Int, count: Int) {
        current.children.subList(index, index + count).clear()
    }

    override fun move(from: Int, to: Int, count: Int) {
        val list = current.children
        val range = ArrayList(list.subList(from, from + count))
        list.subList(from, from + count).clear()
        val target = if (to > from) to - count else to
        list.addAll(target, range)
    }

    override fun onClear() {
        root.children.clear()
    }

    override fun onEndChanges() {
        publish()
    }
}

/** Access and edit this host on the dispatcher that applies its composition changes. */
public class ScopeTreeHost(parent: CompositionContext) : AutoCloseable {
    private val root = LiveNode("", "", ScopeContent())
    private val rootsState = mutableStateOf<List<ScopeTreeNodeSnapshot>>(emptyList())
    private val overrides = mutableMapOf<ScopeAddress, ScopeOverride>()
    private var closed = false
    private val applier = NodeApplier(root) { publish() }
    private val composition = Composition(applier, parent)

    public val roots: List<ScopeTreeNodeSnapshot> get() = rootsState.value

    public fun setContent(content: @Composable () -> Unit) {
        checkOpen()
        composition.setContent(content)
    }

    public fun replaceContent(address: ScopeAddress, content: ScopeContent) {
        edit(address) { it.copy(content = content, definition = null) }
    }

    /** Replace the renderer at this address. Its children are passed to the new definition. */
    public fun replaceDefinition(address: ScopeAddress, definition: ScopeRenderer) {
        edit(address) { it.copy(definition = definition, content = null) }
    }

    public fun resetDefinition(address: ScopeAddress) {
        edit(address) { it.copy(definition = null) }
    }

    /**
     * Install one parameter transform, evaluated against the latest declared parameters.
     * Keep [transform] pure. It replaces any previous transform at this address.
     */
    public fun <P : Any> overrideParameters(
        address: ScopeAddress,
        component: ScopeComponent<P>,
        transform: (declared: P) -> P,
    ) {
        checkOpen()
        require(requireNode(address).component?.parametersFor(component) != null) {
            "The node at $address does not use the given scope component"
        }
        edit(address) { override ->
            override.copy(parameters = { bound ->
                val declared = bound.parametersFor(component)
                if (declared == null) bound else BoundComponent(component, transform(declared))
            })
        }
    }

    public fun resetParameters(address: ScopeAddress) {
        edit(address) { it.copy(parameters = null) }
    }

    public fun setVisibility(address: ScopeAddress, visible: Boolean) {
        edit(address) { it.copy(visible = visible) }
    }

    /** Remove the node and its subtree from the exposed tree until restored. */
    public fun removeNode(address: ScopeAddress) {
        edit(address) { it.copy(removed = true) }
    }

    /** Restore an editor removed node with any other edits still applied. */
    public fun restoreNode(address: ScopeAddress) {
        edit(address) { it.copy(removed = false) }
    }

    public fun resetNode(address: ScopeAddress) {
        edit(address) { ScopeOverride() }
    }

    private fun edit(address: ScopeAddress, update: (ScopeOverride) -> ScopeOverride) {
        checkOpen()
        requireNode(address)
        val previous = overrides[address]
        val empty = ScopeOverride()
        val edited = update(previous ?: empty)
        if (edited == empty) overrides.remove(address) else overrides[address] = edited
        try {
            publish()
        } catch (failure: Throwable) {
            if (previous == null) overrides.remove(address) else overrides[address] = previous
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            composition.dispose()
        } finally {
            root.children.clear()
            overrides.clear()
            rootsState.value = emptyList()
        }
    }

    private fun checkOpen() {
        if (closed) throw IllegalStateException("ScopeTreeHost is closed")
    }

    private fun requireNode(address: ScopeAddress): LiveNode {
        require(address.segments.isNotEmpty()) { "A scope node address must include a root segment" }
        var current: LiveNode = root
        for (segment in address.segments) {
            val next = current.children.firstOrNull { it.segment == segment }
                ?: throw NoSuchElementException("No scope node at $address")
            current = next
        }
        return current
    }

    private fun publish() {
        if (closed) return
        val retainedOverrides = HashSet<ScopeAddress>()
        val roots = snapshotChildren(root, emptyList(), retainedOverrides)
        overrides.keys.retainAll(retainedOverrides)
        rootsState.value = roots
    }

    private fun snapshotChildren(
        parent: LiveNode,
        prefix: List<AddressSegment>,
        retainedOverrides: MutableSet<ScopeAddress>,
    ): List<ScopeTreeNodeSnapshot> {
        val seen = HashSet<AddressSegment>()
        return readOnlyList(parent.children.mapNotNull { child ->
            check(seen.add(child.segment)) {
                "Duplicate scope node site='${child.site}' businessKey='${child.businessKey}' under ${ScopeAddress(prefix)}"
            }
            val path = prefix + child.segment
            val address = ScopeAddress(path)
            val override = overrides[address]
            if (override != null) retainedOverrides.add(address)
            val children = snapshotChildren(child, path, retainedOverrides)
            if (override?.removed == true) return@mapNotNull null
            val component = child.component?.let { override?.parameters?.invoke(it) ?: it }
            ScopeTreeNodeSnapshot(
                address = address,
                businessKey = child.businessKey,
                content = override?.content ?: child.declaredContent,
                visible = override?.visible ?: true,
                children = children,
                component = component,
                definition = override?.definition,
                contentReplaced = override?.content != null,
            )
        })
    }
}

@Composable
public fun ScopeNode(
    site: String,
    businessKey: String,
    content: ScopeContent = ScopeContent(),
    children: @Composable () -> Unit = {},
) {
    EmitScopeNode(site, businessKey, content, null, children)
}

@Composable
public fun <P : Any> ScopeNode(
    site: String,
    businessKey: String,
    component: ScopeComponent<P>,
    parameters: P,
    children: @Composable () -> Unit = {},
) {
    EmitScopeNode(site, businessKey, EmptyScopeContent, BoundComponent(component, parameters), children)
}

private val EmptyScopeContent = ScopeContent()

@Composable
private fun EmitScopeNode(
    site: String,
    businessKey: String,
    content: ScopeContent,
    component: BoundComponent<*>?,
    children: @Composable () -> Unit,
) {
    require(site.isNotBlank()) { "site must not be blank" }
    require(businessKey.isNotBlank()) { "businessKey must not be blank" }
    key(site, businessKey) {
        ComposeNode<LiveNode, NodeApplier>(
            factory = { LiveNode(site, businessKey, content, component) },
            update = {
                set(content) { declaredContent = it }
                set(component) { this.component = it }
            },
            content = children,
        )
    }
}

@Composable
public fun RenderScopeTree(
    roots: List<ScopeTreeNodeSnapshot>,
) {
    RenderTree(roots, null)
}

/** Supply a renderer that takes ownership of rendering each node and its children. */
@Composable
public fun RenderScopeTree(
    roots: List<ScopeTreeNodeSnapshot>,
    renderNode: @Composable (node: ScopeTreeNodeSnapshot, children: @Composable () -> Unit) -> Unit,
) {
    RenderTree(roots, renderNode)
}

private typealias CustomNodeRenderer = @Composable (ScopeTreeNodeSnapshot, @Composable () -> Unit) -> Unit

@Composable
private fun RenderTree(
    roots: List<ScopeTreeNodeSnapshot>,
    renderNode: CustomNodeRenderer?,
) {
    for (node in roots) {
        if (!node.visible) continue
        key(node.address) {
            RenderNode(node, renderNode)
        }
    }
}

@Composable
private fun RenderNode(
    node: ScopeTreeNodeSnapshot,
    renderNode: CustomNodeRenderer?,
) {
    val children = remember(node.children) {
        readOnlyList(node.children.filter { it.visible })
    }
    val slots = ScopeChildren(
        nodes = children,
        renderAll = { RenderTree(children, renderNode) },
        renderNode = { child -> key(child.address) { RenderNode(child, renderNode) } },
    )
    if (renderNode == null) {
        node.Render(slots)
    } else {
        renderNode(node) { slots.Render() }
    }
}
