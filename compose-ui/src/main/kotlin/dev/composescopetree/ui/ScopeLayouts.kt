package dev.composescopetree.ui

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.composescopetree.ScopeComponent
import dev.composescopetree.ScopeNode

public data class BoxParameters(
    public val modifier: Modifier = Modifier,
    public val contentAlignment: Alignment = Alignment.TopStart,
    public val propagateMinConstraints: Boolean = false,
)

public val BoxComponent: ScopeComponent<BoxParameters> = ScopeComponent { parameters, children ->
    Box(parameters.modifier, parameters.contentAlignment, parameters.propagateMinConstraints) {
        children.Render()
    }
}

@Composable
public fun ScopeBox(
    site: String,
    businessKey: String,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    children: @Composable () -> Unit = {},
) {
    ScopeNode(
        site,
        businessKey,
        BoxComponent,
        BoxParameters(modifier, contentAlignment, propagateMinConstraints),
        children,
    )
}

public data class LazyColumnParameters(
    public val modifier: Modifier = Modifier,
    public val state: LazyListState? = null,
    public val contentPadding: PaddingValues = PaddingValues(0.dp),
    public val reverseLayout: Boolean = false,
    public val verticalArrangement: Arrangement.Vertical? = null,
    public val horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    public val flingBehavior: FlingBehavior? = null,
    public val userScrollEnabled: Boolean = true,
)

public val LazyColumnComponent: ScopeComponent<LazyColumnParameters> = ScopeComponent { parameters, children ->
    val defaultState = rememberLazyListState()
    LazyColumn(
        modifier = parameters.modifier,
        state = parameters.state ?: defaultState,
        contentPadding = parameters.contentPadding,
        reverseLayout = parameters.reverseLayout,
        verticalArrangement = parameters.verticalArrangement
            ?: if (parameters.reverseLayout) Arrangement.Bottom else Arrangement.Top,
        horizontalAlignment = parameters.horizontalAlignment,
        flingBehavior = parameters.flingBehavior ?: ScrollableDefaults.flingBehavior(),
        userScrollEnabled = parameters.userScrollEnabled,
    ) {
        items(children.nodes, key = { item ->
            val (site, businessKey) = item.address.segments.last()
            "${site.length}:$site$businessKey"
        }) { item ->
            children.Render(item)
        }
    }
}

/** Declare each list item as a child scope with a stable business key. */
@Composable
public fun ScopeLazyColumn(
    site: String,
    businessKey: String,
    parameters: LazyColumnParameters = LazyColumnParameters(),
    children: @Composable () -> Unit = {},
) {
    ScopeNode(site, businessKey, LazyColumnComponent, parameters, children)
}
