package dev.composescopetree

import androidx.compose.runtime.Composable

public typealias ImplicitCallReplacement = @Composable (
    arguments: ImplicitCallArguments,
    original: @Composable () -> Any?,
) -> Any?

/** Explicit arguments at an instrumented call site, in parameter order. */
public class ImplicitCallArguments internal constructor(
    internal val packedNames: String,
    private val arguments: Array<out Any?>,
) {
    public val names: List<String> get() = if (arguments.isEmpty()) emptyList() else packedNames.split('\u0000')

    public operator fun get(name: String): Any? =
        arguments[argumentIndex(name)]

    /** Access a parameter with the type of the original Kotlin declaration. */
    @Suppress("UNCHECKED_CAST")
    public fun <T> getAs(name: String): T = get(name) as T

    public fun with(name: String, value: Any?): ImplicitCallArguments {
        val index = argumentIndex(name)
        val updated = Array(arguments.size) { if (it == index) value else arguments[it] }
        return ImplicitCallArguments(packedNames, updated)
    }

    /** Indexed access used by the compiler-generated continuation. */
    public fun valueAt(index: Int): Any? = arguments[index]

    private fun argumentIndex(name: String): Int {
        val available = names
        val index = available.indexOf(name)
        require(index >= 0) { "No editable argument named $name. Available arguments: $available" }
        return index
    }

    override fun equals(other: Any?): Boolean =
        other is ImplicitCallArguments && packedNames == other.packedNames && arguments.contentEquals(other.arguments)

    override fun hashCode(): Int = 31 * packedNames.hashCode() + arguments.contentHashCode()
}

internal val ComposableCallComponent = ScopeComponent<ImplicitCallArguments> { _, _ -> }

private val EmptyCallArguments = ImplicitCallArguments("", emptyArray())

/** Build the argument record from compiler-generated slots. */
public fun implicitCallArguments(packedNames: String, vararg arguments: Any?): ImplicitCallArguments =
    if (arguments.isEmpty()) EmptyCallArguments else ImplicitCallArguments(packedNames, arguments)

@Suppress("UNCHECKED_CAST")
public fun <T> implicitCallValue(arguments: ImplicitCallArguments, index: Int): T =
    arguments.valueAt(index) as T

/** Compiler entry point for a regular Composable call, including calls that return a value. */
@Composable
public fun <T> TrackComposableCall(
    site: String,
    function: String,
    returnsUnit: Boolean,
    arguments: ImplicitCallArguments,
    contentName: String?,
    acceptsDefinition: Boolean,
    render: @Composable (ImplicitCallArguments) -> T,
): T {
    if (returnsUnit) {
        TrackMountedUnitScope(site, ComposableCallComponent, arguments, function, acceptsDefinition) { values, edit ->
            val replacement = edit?.callDefinition
            val definition = edit?.definition
            when {
                replacement != null -> replacement(values) { render(values) }
                definition != null -> {
                    val content: (@Composable () -> Unit)? = contentName?.let { values.getAs(it) }
                    definition(content ?: {})
                }
                else -> render(values)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return Unit as T
    }
    return TrackMountedScope(site, ComposableCallComponent, arguments, function) { values, edit ->
        val replacement = edit?.callDefinition
        if (replacement == null) render(values) else {
            @Suppress("UNCHECKED_CAST")
            replacement(values) { render(values) } as T
        }
    }
}
