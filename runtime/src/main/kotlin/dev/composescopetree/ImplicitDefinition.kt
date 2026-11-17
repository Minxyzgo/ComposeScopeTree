package dev.composescopetree

import androidx.compose.runtime.Composable

/** Connects a component's parameters to the exact function type of its original content. */
public class ImplicitDefinition<P : Any, C : Any>(
    public val component: ScopeComponent<P>,
)

internal class TypedDefinition<P : Any, C : Any>(
    private val signature: ImplicitDefinition<P, C>,
    private val renderer: @Composable (P, C) -> Unit,
) {
    internal fun <Params : Any, Content : Any> rendererFor(
        definition: ImplicitDefinition<Params, Content>,
    ): (@Composable (Params, Content) -> Unit)? {
        if (signature !== definition) return null
        // Only the adapter holding this signature can supply its parameters and content.
        @Suppress("UNCHECKED_CAST")
        val typed = this as TypedDefinition<Params, Content>
        return typed.renderer
    }
}

/** Entry point for adapters with a statically typed content slot. */
@Composable
public fun <P : Any, C : Any> TrackImplicitDefinition(
    site: String,
    signature: ImplicitDefinition<P, C>,
    parameters: P,
    content: C,
    function: String,
    render: @Composable (P, C) -> Unit,
) {
    TrackMountedUnitScope(
        site, signature.component, parameters, function,
        acceptsDefinition = false, signature = signature,
    ) { values, edit ->
        val effective = edit?.typedDefinition?.rendererFor(signature) ?: render
        effective(values, content)
    }
}
