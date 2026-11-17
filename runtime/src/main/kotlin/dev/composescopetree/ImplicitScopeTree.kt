package dev.composescopetree

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public typealias ImplicitScopeRenderer = @Composable (content: @Composable () -> Unit) -> Unit

public class ImplicitScopeNode internal constructor(
    public val address: ScopeAddress,
    public val visible: Boolean,
    public val children: List<ImplicitScopeNode>,
    private val component: BoundComponent<*>,
    public val function: String?,
) {
    public val site: String get() = address.segments.last().site
    public val arguments: ImplicitCallArguments? get() = component.parametersFor(ComposableCallComponent)

    public fun <P : Any> parameters(component: ScopeComponent<P>): P? =
        this.component.parametersFor(component)
}

private val LocalHost = staticCompositionLocalOf<ImplicitScopeTreeHost?> { null }
private val LocalAddress = compositionLocalOf<ScopeAddress?> { null }

/** Compiler fast path for calls outside an implicit tree. */
@Composable
@ReadOnlyComposable
public fun isImplicitScopeTrackingEnabled(): Boolean = LocalHost.current != null

internal data class ImplicitEdit(
    val parameters: ((BoundComponent<*>) -> BoundComponent<*>)? = null,
    val definition: ImplicitScopeRenderer? = null,
    val callDefinition: ImplicitCallReplacement? = null,
    val typedDefinition: TypedDefinition<*, *>? = null,
    val visible: Boolean = true,
    val removed: Boolean = false,
)

internal class MountedScope(
    val address: ScopeAddress,
    val parent: ScopeAddress?,
    val sequence: Long,
    binding: BoundComponent<*>,
    val function: String?,
    val returnsUnit: Boolean,
    val acceptsDefinition: Boolean,
    val signature: ImplicitDefinition<*, *>?,
) {
    var binding by mutableStateOf(binding)
    var edit by mutableStateOf(ImplicitEdit())
}

/** Observes automatically instrumented calls in a normal UI composition. */
public class ImplicitScopeTreeHost {
    @OptIn(ExperimentalAtomicApi::class)
    private val sequence = AtomicLong(0)
    private val mounted = mutableStateMapOf<ScopeAddress, MountedScope>()
    private val topology by derivedStateOf {
        mounted.values.sortedBy { it.sequence }.groupBy { it.parent }
    }
    private val rootSnapshots by derivedStateOf {
        val childrenByParent = topology
        fun childrenOf(parent: ScopeAddress?): List<ImplicitScopeNode> =
            readOnlyList(childrenByParent[parent].orEmpty().mapNotNull { scope ->
                val edit = scope.edit
                if (edit.removed) return@mapNotNull null
                val binding = scope.binding
                ImplicitScopeNode(
                    scope.address, edit.visible, childrenOf(scope.address),
                    edit.parameters?.invoke(binding) ?: binding, scope.function,
                )
            })
        childrenOf(null)
    }

    public val roots: List<ImplicitScopeNode> get() = rootSnapshots

    @Composable
    public fun Content(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalHost provides this, LocalAddress provides null, content = content)
    }

    /** Apply a pure transform to this instance's latest arguments. */
    public fun <P : Any> overrideParameters(
        address: ScopeAddress,
        component: ScopeComponent<P>,
        transform: (P) -> P,
    ) {
        require(mounted[address]?.binding?.parametersFor(component) != null) {
            "The node at $address does not use the given scope component"
        }
        edit(address) { previous ->
            previous.copy(parameters = { binding ->
                val parameters = binding.parametersFor(component)
                if (parameters == null) binding else BoundComponent(component, transform(parameters))
            })
        }
    }

    public fun replaceDefinition(address: ScopeAddress, render: ImplicitScopeRenderer) {
        requireUnitCall(address)
        require(mounted[address]?.acceptsDefinition == true) {
            "This call requires a typed definition or replaceCall: $address"
        }
        edit(address) { it.copy(definition = render, callDefinition = null) }
    }

    /** Pass the latest arguments and original content without binding its receiver. */
    public fun <P : Any, C : Any> replaceDefinition(
        address: ScopeAddress,
        signature: ImplicitDefinition<P, C>,
        render: @Composable (parameters: P, content: C) -> Unit,
    ) {
        require(mounted[address]?.signature === signature) {
            "The node at $address does not use the given definition signature"
        }
        edit(address) { it.copy(typedDefinition = TypedDefinition(signature, render)) }
    }

    /** Replace a generic call, with access to its arguments and original invocation. */
    public fun replaceCall(address: ScopeAddress, render: ImplicitCallReplacement) {
        require(mounted[address]?.binding?.parametersFor(ComposableCallComponent) != null) {
            "The node at $address is not a generic Composable call"
        }
        edit(address) { it.copy(definition = null, callDefinition = render) }
    }

    /** Replace a generic call while forwarding a slot with its declared receiver and parameters. */
    public fun <C> replaceCallWithSlot(
        address: ScopeAddress,
        slot: String,
        render: @Composable (arguments: ImplicitCallArguments, content: C) -> Any?,
    ) {
        val arguments = mounted[address]?.binding?.parametersFor(ComposableCallComponent)
        require(arguments != null && slot in arguments.names) {
            "No supplied argument named $slot at $address"
        }
        replaceCall(address) { values, _ -> render(values, values.getAs<C>(slot)) }
    }

    public fun resetDefinition(address: ScopeAddress) {
        edit(address) { it.copy(definition = null, callDefinition = null, typedDefinition = null) }
    }

    public fun resetParameters(address: ScopeAddress) {
        edit(address) { it.copy(parameters = null) }
    }

    public fun setVisibility(address: ScopeAddress, visible: Boolean) {
        requireUnitCall(address)
        edit(address) { it.copy(visible = visible) }
    }

    public fun removeNode(address: ScopeAddress) {
        requireUnitCall(address)
        edit(address) { it.copy(removed = true) }
    }

    public fun restoreNode(address: ScopeAddress) {
        edit(address) { it.copy(removed = false) }
    }

    public fun resetNode(address: ScopeAddress) {
        edit(address) { ImplicitEdit() }
    }

    private fun edit(address: ScopeAddress, update: (ImplicitEdit) -> ImplicitEdit) {
        val node = requireNotNull(mounted[address]) { "No mounted implicit node at $address" }
        node.edit = update(node.edit)
    }

    private fun requireUnitCall(address: ScopeAddress) {
        require(mounted[address]?.returnsUnit == true) {
            "Only a mounted Unit-returning call can be hidden, removed, or replaced: $address"
        }
    }

    public fun overrideArguments(
        address: ScopeAddress,
        transform: (ImplicitCallArguments) -> ImplicitCallArguments,
    ) {
        overrideParameters(address, ComposableCallComponent) { declared ->
            transform(declared).also { edited ->
                require(edited.packedNames == declared.packedNames) {
                    "An argument override must preserve the original parameter names and order"
                }
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    internal fun createScope(
        site: String,
        parent: ScopeAddress?,
        binding: BoundComponent<*>,
        function: String?,
        returnsUnit: Boolean,
        acceptsDefinition: Boolean,
        signature: ImplicitDefinition<*, *>?,
    ): MountedScope {
        val id = sequence.addAndFetch(1)
        val address = ScopeAddress(parent?.segments.orEmpty() + AddressSegment(site, id.toString()))
        return MountedScope(address, parent, id, binding, function, returnsUnit, acceptsDefinition, signature)
    }

    internal fun mount(scope: MountedScope) {
        mounted[scope.address] = scope
    }

    internal fun unmount(scope: MountedScope) {
        mounted.remove(scope.address)
    }
}

/** Rendering entry point for compiler adapters. */
@Composable
public fun <P : Any> TrackImplicitScope(
    site: String,
    component: ScopeComponent<P>,
    parameters: P,
    function: String? = null,
    render: @Composable (P, ImplicitScopeRenderer?) -> Unit,
) {
    TrackMountedUnitScope(site, component, parameters, function, acceptsDefinition = true) { values, edit ->
        render(values, edit?.definition)
    }
}

@Composable
internal fun <P : Any> TrackMountedUnitScope(
    site: String,
    component: ScopeComponent<P>,
    parameters: P,
    function: String?,
    acceptsDefinition: Boolean,
    signature: ImplicitDefinition<*, *>? = null,
    render: @Composable (P, ImplicitEdit?) -> Unit,
) {
    val scope = rememberMountedScope(site, component, parameters, function, true, acceptsDefinition, signature)
    if (scope == null) {
        render(parameters, null)
        return
    }
    val edit = scope.edit
    if (edit.removed || !edit.visible) return
    val effective = edit.effectiveParameters(component, parameters)
    CompositionLocalProvider(LocalAddress provides scope.address) { render(effective, edit) }
}

@Composable
internal fun <P : Any, T> TrackMountedScope(
    site: String,
    component: ScopeComponent<P>,
    parameters: P,
    function: String?,
    render: @Composable (P, ImplicitEdit?) -> T,
): T {
    val scope = rememberMountedScope(site, component, parameters, function, false, false, null)
        ?: return render(parameters, null)
    val edit = scope.edit
    val effective = edit.effectiveParameters(component, parameters)
    var result: T? = null
    CompositionLocalProvider(LocalAddress provides scope.address) { result = render(effective, edit) }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

@Composable
private fun <P : Any> rememberMountedScope(
    site: String,
    component: ScopeComponent<P>,
    parameters: P,
    function: String?,
    returnsUnit: Boolean,
    acceptsDefinition: Boolean,
    signature: ImplicitDefinition<*, *>?,
): MountedScope? {
    val host = LocalHost.current ?: return null
    val parent = LocalAddress.current
    val declared = remember(component, parameters, signature) {
        BoundComponent(component, parameters)
    }
    val registration = remember(host, parent, site, signature) {
        host.createScope(site, parent, declared, function, returnsUnit, acceptsDefinition, signature)
    }
    DisposableEffect(host, registration) {
        host.mount(registration)
        onDispose { host.unmount(registration) }
    }
    SideEffect { registration.binding = declared }
    return registration
}

private fun <P : Any> ImplicitEdit.effectiveParameters(component: ScopeComponent<P>, parameters: P): P =
    this.parameters?.invoke(BoundComponent(component, parameters))?.parametersFor(component) ?: parameters
