package dev.composescopetree.compiler

import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrBreakContinue
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class ScopeTreeIrGenerationExtension : IrGenerationExtension {
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val markerId = CallableId(FqName("dev.composescopetree"), Name.identifier("scopeSite"))
        val stringType = pluginContext.irBuiltIns.stringType
        for (file in moduleFragment.files) {
            val finder = pluginContext.finderForSource(file)
            val marker = finder.findFunctions(markerId).singleOrNull {
                val function = it.owner
                function.parameters.isEmpty() && function.typeParameters.isEmpty() &&
                    function.returnType == stringType
            }
            val adapters = mutableMapOf<IrSimpleFunctionSymbol, IrSimpleFunctionSymbol>()
            val generic = if (file.packageFqName == adapterPackage) null else GenericComposableCall.find(pluginContext, file)
            if (file.packageFqName != adapterPackage) {
                for (spec in layoutAdapters) {
                    val original = finder.findFunctions(CallableId(layoutPackage, Name.identifier(spec.name)))
                        .singleOrNull { function -> function.owner.parameters.map { it.name.asString() } == spec.parameters }
                        ?: continue
                    val adapter = finder.findFunctions(CallableId(adapterPackage, Name.identifier(spec.adapter)))
                        .singleOrNull() ?: continue
                    adapters[original] = adapter
                }
            }
            file.transformChildren(ScopeSiteTransformer(file, marker, adapters, stringType, generic), null)
        }
    }

    private val adapterPackage = FqName("dev.composescopetree.ui")
    private val layoutPackage = FqName("androidx.compose.foundation.layout")
    private val layoutAdapters = listOf(
        LayoutAdapter("Box", "implicitBox", listOf("modifier", "contentAlignment", "propagateMinConstraints", "content")),
        LayoutAdapter("Box", "implicitEmptyBox", listOf("modifier")),
        LayoutAdapter("Row", "implicitRow", listOf("modifier", "horizontalArrangement", "verticalAlignment", "content")),
        LayoutAdapter("Column", "implicitColumn", listOf("modifier", "verticalArrangement", "horizontalAlignment", "content")),
    )

    private data class LayoutAdapter(val name: String, val adapter: String, val parameters: List<String>)

    private class ScopeSiteTransformer(
        private val file: IrFile,
        private val marker: IrSimpleFunctionSymbol?,
        private val adapters: Map<IrSimpleFunctionSymbol, IrSimpleFunctionSymbol>,
        private val stringType: IrType,
        private val generic: GenericComposableCall?,
    ) : IrElementTransformerVoidWithContext() {
        @OptIn(UnsafeDuringIrConstructionAPI::class)
        override fun visitCall(expression: IrCall): IrExpression {
            expression.transformChildren(this, null)

            if (expression.startOffset < 0) return expression
            if (expression.symbol == marker) return siteConstant(expression)
            val adapter = adapters[expression.symbol]
            val owner = expression.symbol.owner
            val composableInvoke = owner.name.asString() == "invoke" &&
                expression.dispatchReceiver?.type?.hasAnnotation(composableAnnotation) == true
            if (adapter == null && (generic == null || (!owner.hasAnnotation(composableAnnotation) && !composableInvoke))) {
                return expression
            }
            // The adapter and original call still have source signatures here.
            // After Compose lowering the synthesized Composer arguments cannot be copied this way.
            if (expression.arguments.any { it?.hasNonLocalControlFlow() == true }) return expression
            if (adapter == null) {
                val name = owner.fqNameWhenAvailable?.asString() ?: owner.name.asString()
                if (name.startsWith("androidx.compose.runtime.") || name in trackerEntrypoints) return expression
                val scope = currentScope ?: return expression
                val parent = currentDeclarationParent ?: return expression
                return generic!!.wrap(expression, siteConstant(expression), name, scope.scope.scopeOwnerSymbol, parent)
            }
            if (expression.arguments.size + 1 != adapter.owner.parameters.size) return expression
            return IrCallImpl(
                startOffset = expression.startOffset,
                endOffset = expression.endOffset,
                type = expression.type,
                symbol = adapter,
                origin = expression.origin,
            ).apply {
                for (index in expression.arguments.indices) {
                    arguments[index] = expression.arguments[index]
                }
                arguments[expression.arguments.size] = siteConstant(expression)
            }
        }

        private fun siteConstant(expression: IrCall): IrExpression {
            val fileEntry = file.fileEntry
            val rawName = fileEntry.name
            val baseName = rawName.substringAfterLast('/').substringAfterLast('\\')
            val packageName = file.packageFqName.asString()
            val line = fileEntry.getLineNumber(expression.startOffset) + 1
            val column = fileEntry.getColumnNumber(expression.startOffset) + 1
            val value = buildString {
                if (packageName.isNotEmpty()) {
                    append(packageName)
                    append('.')
                }
                append(baseName)
                append(':')
                append(line)
                append(':')
                append(column)
            }

            return IrConstImpl.string(
                expression.startOffset,
                expression.endOffset,
                stringType,
                value,
            )
        }
    }
}

internal val composableAnnotation = FqName("androidx.compose.runtime.Composable")

private val trackerEntrypoints = setOf(
    "dev.composescopetree.ImplicitScopeTreeHost.Content",
    "dev.composescopetree.TrackComposableCall",
    "dev.composescopetree.TrackImplicitScope",
    "dev.composescopetree.TrackImplicitDefinition",
    "dev.composescopetree.ScopeNode",
    "dev.composescopetree.RenderScopeTree",
    "dev.composescopetree.ScopeChildren.Render",
    "dev.composescopetree.ScopeTreeNodeSnapshot.Render",
    "dev.composescopetree.ui.ScopeBox",
    "dev.composescopetree.ui.ScopeLazyColumn",
)

private fun IrExpression.hasNonLocalControlFlow(): Boolean {
    val localTargets = HashSet<IrReturnTargetSymbol>()
    val returnedTo = HashSet<IrReturnTargetSymbol>()
    val localLoops = HashSet<IrLoop>()
    val jumpedTo = HashSet<IrLoop>()
    accept(object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
            element.acceptChildren(this, null)
        }

        override fun visitFunction(declaration: IrFunction) {
            localTargets.add(declaration.symbol)
            super.visitFunction(declaration)
        }

        override fun visitReturnableBlock(expression: IrReturnableBlock) {
            localTargets.add(expression.symbol)
            super.visitReturnableBlock(expression)
        }

        override fun visitReturn(expression: IrReturn) {
            returnedTo.add(expression.returnTargetSymbol)
            super.visitReturn(expression)
        }

        override fun visitLoop(loop: IrLoop) {
            localLoops.add(loop)
            super.visitLoop(loop)
        }

        override fun visitBreakContinue(jump: IrBreakContinue) {
            jumpedTo.add(jump.loop)
            super.visitBreakContinue(jump)
        }
    }, null)
    return returnedTo.any { it !in localTargets } || jumpedTo.any { it !in localLoops }
}
