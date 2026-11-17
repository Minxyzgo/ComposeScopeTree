# Compose Scope Tree

每次显式声明的 Compose scope 都对应一棵可编辑树中的一个节点。Composable 重新运行时，Compose Runtime 决定何时插入、移动、更新和删除声明节点；程序和编辑器通过节点地址修改生效的树。声明数据和运行时覆盖分开保存。同一个节点在重新组合、兄弟重排后保留原有覆盖，节点移除时清理覆盖。

## 节点身份

节点地址由父地址、编译器生成的调用点标识、不可变业务键逐级组成。列表项使用稳定的非空业务键，例如单位 ID。在同一父节点下，重复的调用点与业务键组合报出明确错误。调用点标识供同一构建内定位和重组，可使用包名、文件名和源码位置。跨构建保存的编辑数据需要另设显式持久标识。

## 编译器与运行时

- 显式声明模式下，Kotlin K2 IR 插件将普通 Kotlin 标记函数 `scopeSite()` 改写为调用点标识。
- 业务代码用 `ScopeNode(scopeSite(), key, ...)` 声明一个 scope。内部用公开的 `ComposeNode`、`AbstractApplier` 和 `key` 建立和更新树。树宿主使用调用方管理的 `CompositionContext`，Compose 的状态和副作用仍遵循正常生命周期。
- 树提供只读快照和按地址修改节点的接口。节点的有效内容可包含可替换的渲染数据和可组合渲染器，另支持可见性及重置。有效渲染树由独立的常规 Compose UI 合成消费。这允许应用在渲染阶段使用已有的 Compose UI 组件，也为其他渲染器保留了入口。
- 应用逐步将所需区域声明成节点。任意 Compose UI 库的内部布局节点保持原有所有权。RemoteCompose 的「描述树与播放器分开」可供参考，序列化和跨进程渲染属于后续工作。

## 验收

JVM 样例和测试实际使用两套编译器插件。测试验证生成的调用点标识、嵌套地址、更新和重排时保留覆盖、子节点移除清理覆盖，以及运行时替换内容、修改可见性并重置。测试通过正常的 Compose Runtime 和真实重组完成。

## 现有界面的隐式节点

K2 IR 插件在 Compose 编译器之前识别普通的 `@Composable` 调用。它为通用调用生成可读取实参的调用入口，并保留原函数、返回类型和 Kotlin 默认参数机制。`Box`、`Row` 和 `Column` 还提供类型化适配器。

应用用 `ImplicitScopeTreeHost.Content` 包住需观察的界面。运行时在原有 UI 合成中登记已经挂载的实例，按地址编辑实参或替换这次调用。型别兼容的布局替换通过 `BoxDefinition`、`RowDefinition` 或 `ColumnDefinition` 获得原始内容函数，由新的布局提供接收者。通用替换可以按原声明类型转发内容的接收者与参数，实际调用它们的值由替代函数提供。相同调用点的多个实例有不同地址，正常使用 Compose 的 `key` 可让实例在重排时保持身份。

这一模式从实际合成中发现节点。未挂载的懒列表项需要由数据源提供地址，独立声明树的 `ScopeLazyColumn` 可以处理这种情况。`sample:runImplicit` 使用 Compose UI 离屏场景演示普通调用的自动发现、尺寸修改和内容复用。

## 参考

- [Kotlin 自定义编译器插件](https://kotlinlang.org/docs/custom-compiler-plugins.html)
- [ComposeNode API](https://developer.android.com/reference/kotlin/androidx/compose/runtime/ComposeNode.composable)
- [AndroidX RemoteCompose 架构](https://android.googlesource.com/platform/frameworks/support/%2Bshow/e440523da2bc44cdc0e5a5e1311614ca78be28f4/compose/remote/remote-core/doc/REMOTE_COMPOSE_ARCHITECTURE.md)
