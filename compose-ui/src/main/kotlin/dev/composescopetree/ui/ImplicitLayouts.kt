package dev.composescopetree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.composescopetree.ImplicitDefinition
import dev.composescopetree.ScopeComponent
import dev.composescopetree.TrackImplicitDefinition
import dev.composescopetree.shouldTrackImplicitScope

public val BoxDefinition: ImplicitDefinition<BoxParameters, @Composable BoxScope.() -> Unit> =
    ImplicitDefinition(BoxComponent)

/** Compiler entry point for the content overload of Box. */
@Composable
public fun implicitBox(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
    site: String,
) {
    if (!shouldTrackImplicitScope(site, "androidx.compose.foundation.layout.Box")) {
        Box(modifier, contentAlignment, propagateMinConstraints, content)
        return
    }
    TrackImplicitDefinition(
        site, BoxDefinition, BoxParameters(modifier, contentAlignment, propagateMinConstraints),
        content, "androidx.compose.foundation.layout.Box",
    ) { p, child ->
        Box(p.modifier, p.contentAlignment, p.propagateMinConstraints, child)
    }
}

/** Compiler entry point for the empty overload of Box. */
@Composable
public fun implicitEmptyBox(modifier: Modifier, site: String) {
    if (!shouldTrackImplicitScope(site, "androidx.compose.foundation.layout.Box")) {
        Box(modifier)
        return
    }
    TrackImplicitDefinition(
        site, BoxDefinition, BoxParameters(modifier), {}, "androidx.compose.foundation.layout.Box",
    ) { parameters, _ ->
        Box(parameters.modifier)
    }
}

public data class RowParameters(
    public val modifier: Modifier = Modifier,
    public val horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    public val verticalAlignment: Alignment.Vertical = Alignment.Top,
)

public val RowComponent: ScopeComponent<RowParameters> = ScopeComponent { p, children ->
    Row(p.modifier, p.horizontalArrangement, p.verticalAlignment) { children.Render() }
}

public val RowDefinition: ImplicitDefinition<RowParameters, @Composable RowScope.() -> Unit> =
    ImplicitDefinition(RowComponent)

@Composable
public fun implicitRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable RowScope.() -> Unit,
    site: String,
) {
    if (!shouldTrackImplicitScope(site, "androidx.compose.foundation.layout.Row")) {
        Row(modifier, horizontalArrangement, verticalAlignment, content)
        return
    }
    TrackImplicitDefinition(
        site, RowDefinition, RowParameters(modifier, horizontalArrangement, verticalAlignment),
        content, "androidx.compose.foundation.layout.Row",
    ) { p, child ->
        Row(p.modifier, p.horizontalArrangement, p.verticalAlignment, child)
    }
}

public data class ColumnParameters(
    public val modifier: Modifier = Modifier,
    public val verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    public val horizontalAlignment: Alignment.Horizontal = Alignment.Start,
)

public val ColumnComponent: ScopeComponent<ColumnParameters> = ScopeComponent { p, children ->
    Column(p.modifier, p.verticalArrangement, p.horizontalAlignment) { children.Render() }
}

public val ColumnDefinition: ImplicitDefinition<ColumnParameters, @Composable ColumnScope.() -> Unit> =
    ImplicitDefinition(ColumnComponent)

@Composable
public fun implicitColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
    site: String,
) {
    if (!shouldTrackImplicitScope(site, "androidx.compose.foundation.layout.Column")) {
        Column(modifier, verticalArrangement, horizontalAlignment, content)
        return
    }
    TrackImplicitDefinition(
        site, ColumnDefinition, ColumnParameters(modifier, verticalArrangement, horizontalAlignment),
        content, "androidx.compose.foundation.layout.Column",
    ) { p, child ->
        Column(p.modifier, p.verticalArrangement, p.horizontalAlignment, child)
    }
}
