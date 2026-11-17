package dev.composescopetree

import androidx.compose.runtime.Composable

public typealias ScopeRenderer = @Composable (children: ScopeChildren) -> Unit

/** Declare a component once and reuse the same instance at each call site. */
public class ScopeComponent<P : Any>(
    internal val render: @Composable (parameters: P, children: ScopeChildren) -> Unit,
)

/** The existing visible children, available as a group or as individual layout slots. */
public class ScopeChildren internal constructor(
    public val nodes: List<ScopeTreeNodeSnapshot>,
    private val renderAll: @Composable () -> Unit,
    private val renderNode: @Composable (ScopeTreeNodeSnapshot) -> Unit,
) {
    @Composable
    public fun Render() {
        renderAll()
    }

    /** Call once per child when the container controls child placement, such as a lazy list. */
    @Composable
    public fun Render(node: ScopeTreeNodeSnapshot) {
        renderNode(node)
    }
}

internal class BoundComponent<P : Any>(
    private val component: ScopeComponent<P>,
    private val parameters: P,
) {
    internal fun <T : Any> parametersFor(type: ScopeComponent<T>): T? {
        if (component !== type) return null
        // Component identity ensures that both instances have the same parameter type.
        @Suppress("UNCHECKED_CAST")
        return parameters as T
    }

    @Composable
    internal fun Render(children: ScopeChildren) {
        component.render(parameters, children)
    }
}
