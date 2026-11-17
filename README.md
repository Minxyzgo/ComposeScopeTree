# ComposeScopeTree

A Kotlin library for Android and Compose Desktop that exposes an observable tree
of composition scopes. The K2 compiler plugin can discover calls in an
existing UI, or assign source addresses to `ScopeNode` declarations in a
separate tree. Both modes expose snapshots and per-instance editing.

## Modules

- `runtime` — shared node declarations, typed components, tree editing and rendering.
- `compiler-plugin` — K2 IR plugin that rewrites `scopeSite()` call sites to a
  unique, stable string marker.
- `compose-ui` — shared Compose Foundation adapters, including typed `Box`, `Row`
  and `Column` replacements and the explicit `ScopeLazyColumn` adapter.
- `sample` — headless example (`HeadlessSampleKt`) plus behavior tests that drive
  a real `Recomposer` and `Composition` with a `UnitApplier`.
- `android-sample` — Android Activity using the compiler plugin to find an ordinary
  `Box` and edit its modifier at runtime.

## Build & run

JDK 25 with bytecode target 17. Kotlin 2.4.20 / Gradle 9.6.1.

### Desktop

```
./gradlew test
./gradlew :sample:run
```

`./gradlew test` runs the behavior tests using an actual Compose `Recomposer`
with `Composition`s built on a minimal `UnitApplier`; no UI automation, no
network access. The sample edits one parameter, replaces a container while
rendering its existing children, and removes and restores a node.

### Android

Install Android SDK Platform 37.0 and Build Tools 36.0.0. Set `ANDROID_HOME`
or set `sdk.dir` in your local `local.properties`. The Android targets use AGP
9.1.1 and support API 26 and above. Enable them with `-Pandroid`:

```sh
./gradlew -Pandroid :android-sample:assembleDebug
./gradlew -Pandroid :runtime:bundleAndroidMainAar :compose-ui:bundleAndroidMainAar
```

You can run the sample from Android Studio or use
`./gradlew -Pandroid :android-sample:installDebug` with a device or emulator
connected. Tap **Expand box with a scope edit** to resize the observed box.

In an Android app, add `implementation(project(":compose-ui"))` to get the
Android variant and its runtime dependency. Add the compiler JAR to the
Kotlin compiler arguments of each module whose call sites you want to discover.
See [the Android sample build](android-sample/build.gradle.kts) for the
configuration. For a KMP consumer, declare the dependency in the source set
that uses it. The ComposeScopeTree targets share source and expose the same
APIs. The Android sample resolves AndroidX Compose 1.12.1, and Desktop keeps
Compose Multiplatform 1.13.0-beta01.

Wrap the existing Compose UI at an Android composition root in the same way
as on Desktop:

```kotlin
val host = ImplicitScopeTreeHost()
setContent {
    host.Content { ExistingScreen() }
}
```

## Using the runtime

### Discover ordinary Compose calls

For implicit scopes, depend on `:runtime` and configure the compiler plugin
as in the [Desktop sample](sample/build.gradle.kts) or
[Android sample](android-sample/build.gradle.kts). Add `:compose-ui` to get
typed edits for `Box`, `Row`, and `Column`. Run the scope plugin before the Compose
compiler:

```text
-Xcompiler-plugin-order=dev.composescopetree.compiler>androidx.compose.compiler.plugins.kotlin
```

Wrap the existing UI once at the composition root:

```kotlin
val host = ImplicitScopeTreeHost()

// Inside the UI composition:
host.Content {
    Box(Modifier.padding(8.dp)) { InventoryTitle() }
}

// From the editor, after composition has applied:
val target = host.roots.single().address
host.overrideParameters(target, BoxComponent) { p ->
    p.copy(modifier = p.modifier.padding(12.dp))
}
host.replaceDefinition(target, BoxDefinition) { parameters, content ->
    Box(
        modifier = parameters.modifier,
        contentAlignment = Alignment.BottomEnd,
        propagateMinConstraints = parameters.propagateMinConstraints,
        content = content,
    )
}
```

The compiler instruments Composable call sites, including calls to custom
and library functions. Each mounted call reports its function name, source
location, and instance address in `host.roots`; use that address for edits.
Compose keys preserve an instance through keyed reorders. An unmounted
instance loses its edits. A replacement can create a new composition for its
children, in which case their instance addresses and edits are recreated.

### Preserve a content slot's receiver

`BoxDefinition`, `RowDefinition`, and `ColumnDefinition` pass the effective
layout arguments and the original content function with its declared type.
For example, the new `Row` supplies the receiver for the existing
`@Composable RowScope.() -> Unit`:

```kotlin
host.replaceDefinition(rowAddress, RowDefinition) { p, content ->
    Row(p.modifier, Arrangement.spacedBy(8.dp), p.verticalAlignment, content)
}
```

The receiver type must match the destination slot. To keep Row-specific
content inside a Box, render a Row inside the Box, for example
`Box { Row(content = content) }`.

For a generic call, supply the original slot type at the replacement site.
For example, forward a slot declared as `@Composable ItemScope.(Int) -> Unit`:

```kotlin
host.replaceCallWithSlot(address, "content") { args, content: @Composable ItemScope.(Int) -> Unit ->
    ReplacementPanel(args.getAs("label"), content)
}
```

The replacement provides the receiver and any slot arguments when it invokes
the function. Calling the original invocation through `replaceCall` preserves
any receiver and argument values computed inside the original function.

### Edit generic Composable calls

For a generic call, `node.arguments?.names` lists the editable arguments.
For example, given a plain `CustomPanel(label = "Original") { ... }`:

```kotlin
host.overrideArguments(address) { it.with("label", "Edited") }
host.replaceDefinition(address) { content -> Row { content() } }
```

`replaceDefinition` accepts a `Unit` call with a single supplied
`@Composable () -> Unit` slot, or a call with no slots. Use
`replaceCall(address) { arguments, original -> ... }` for multiple slots,
receiver slots or a call that returns a value. It can invoke `original()`,
read slots using their declaration type via `arguments.getAs<T>(name)`, or
return a replacement of the original result type. Argument overrides must
use the declaration's types. Omitted arguments still use Kotlin's defaults.
Inlined lambda parameters stay at their original call and are excluded from
the editable arguments.

The implicit tree observes mounted calls. Calls inside a lazy item can appear
while Compose has composed that item. A plain `LazyColumn` is itself editable
through the generic API. Use the explicit `ScopeLazyColumn` adapter below if
you need addresses for every item before it is composed. Compose runtime
primitives and calls with nonlocal control flow keep their original form.
Precompiled library bodies require recompilation with the plugin to expose
their internal calls.

Run `./gradlew :sample:runImplicit` for an offscreen Compose UI example.

### Verify behavior and measure overhead

```sh
./gradlew -PverifyScopeTreeIr test
./gradlew -PwithoutScopeTreeCompiler :sample:benchmarkImplicit --args=plain
./gradlew :sample:benchmarkImplicit --args=plain
./gradlew :sample:benchmarkImplicit --args=tracked
```

The tests include an offscreen UI with receiver slots, composable defaults and
return values, typed layout replacement, keyed reorder and removal, lazy scroll,
and argument evaluation both inside and outside a host. The benchmark compares
1,000 small keyed Composables in the same Compose Runtime workload. It reports
cold mount time, warmed median and p95 recomposition times, current-thread
allocations when supported, and the cost of editing one tracked instance.
Drawing and application-specific workload are outside this benchmark.

Outside a host, eligible calls take the original call path without recording
arguments. Inside a host, edits invalidate only the target's state and the
inspector can reuse unchanged snapshots. Tracking still adds work to the
initial mount and to frames that update many recorded arguments. Mount
`host.Content` around the UI you intend to inspect. The implicit snapshot lists
siblings in mount order, so a keyed reorder preserves their identity without
changing the inspector's ordering.

### Declare a separate editable tree

The runtime API is designed so that a normal Compose renderer (Compose UI or a
custom applier composed with `RenderScopeTree`) reads from `ScopeTreeHost.roots`
and re-renders through the standard Compose recomposition loop. The sample and
tests drive this via an `Applier<Unit>` so no `compose.ui` module is required.

```kotlin
// Use the application's running Recomposer or another CompositionContext.
val host = ScopeTreeHost(parentContext)

host.setContent {
    ScopeNode(site = scopeSite(), businessKey = "root") {
        ScopeNode(site = scopeSite(), businessKey = "child", content = ScopeContent("hello"))
    }
}

uiComposition.setContent {
    RenderScopeTree(roots = host.roots)
}
```

Apply the Kotlin Compose compiler plugin and add the ScopeTree compiler plugin
JAR to each module that calls `scopeSite()`. See [the sample Gradle build](sample/build.gradle.kts)
for the compiler argument. A site ID is tied to the source location in the
current build. Use an explicit `site` value if your application needs a
persistent ID across source changes.

`ScopeTreeHost` exposes:

- `roots: List<ScopeTreeNodeSnapshot>`
- `setContent(content)` — attach the root composition
- `replaceContent(address, content)` — override a node's content
- `setVisibility(address, visible)` — hide/show a node snapshot
- `removeNode(address)` / `restoreNode(address)` — remove the subtree from the
  exposed tree and restore it with its edits. The source declaration stays active.
- `resetNode(address)` — clear all edits at the address
- `close()` — dispose the declaration composition and clear the tree

An address contains every `(site, businessKey)` pair from the root to the node.
An override stays with its address through updates and sibling reorders. Once
an applied tree removes an address, inserting it again starts with its declared
content and default visibility. Perform host edits on the dispatcher that
applies the declaration composition's changes.

Sibling nodes, including roots, must have distinct `(site, businessKey)` pairs.
Hiding a node omits its subtree from rendering while keeping it in the snapshot
for editing. For `remember` state in a declaration loop, put Compose's
`key(businessKey)` around the loop body as usual.

## Edit a Box or swap its layout

Add `implementation(project(":compose-ui"))`. In the host declaration, use
`ScopeBox` with the same layout arguments as `Box`. Declare nested scopes in
its child block. Wrap ordinary Compose UI in a leaf's `ScopeContent(render = { … })`.

```kotlin
host.setContent {
    ScopeBox(
        site = scopeSite(),
        businessKey = "inventory",
        modifier = Modifier.padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        ScopeNode(
            site = scopeSite(),
            businessKey = "title",
            content = ScopeContent(render = { InventoryTitle() }),
        )
    }
}

val address = host.roots.single { it.businessKey == "inventory" }.address
host.overrideParameters(address, BoxComponent) { declared ->
    declared.copy(modifier = declared.modifier.padding(12.dp))
}

// Swap the outer layout and render the same children inside it.
host.replaceDefinition(address) { children ->
    Row(Modifier.padding(16.dp)) { children.Render() }
}
```

`BoxParameters` also exposes `contentAlignment` and `propagateMinConstraints`.
Return a new `BoxParameters` to replace all three arguments. Each node has one
pure transform, evaluated with its current declared parameters. Check effective
values with `snapshot.parameters(BoxComponent)` and clear the transform with
`resetParameters(address)`. Use `resetDefinition(address)` to restore the
declared renderer while keeping parameter edits.

Render with `RenderScopeTree(host.roots)` to apply component definitions and
overrides. Replacing the definition reuses the child declarations, addresses
and edits. A new layout can recreate child compositions and their local
`remember` state. Layout-specific child modifiers follow the rules of the
destination layout.

To adapt your own Composable, define a `ScopeComponent<Parameters>` once, call
your original function in its renderer, and declare instances with the typed
`ScopeNode` overload. The parameter type can include any arguments your function
accepts. The compiler plugin provides call site IDs for these explicit scopes.

## Lazy lists

Use `ScopeLazyColumn` and declare one child scope per item with a stable business
key. The item declarations supply addresses even while the rows are off screen.
The adapter feeds visible snapshots to Compose's keyed `items` and renders each
row on demand. A custom container can likewise use `children.nodes` with
`children.Render(node)` for one child at a time.

## License

ComposeScopeTree is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
