package dev.composescopetree.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class GenericComposableCall private constructor(
    private val context: IrPluginContext,
    private val tracker: IrSimpleFunctionSymbol,
    private val createArguments: IrSimpleFunctionSymbol,
    private val readArgument: IrSimpleFunctionSymbol,
    private val isTrackingEnabled: IrSimpleFunctionSymbol,
) {
    fun wrap(
        call: IrCall,
        site: IrExpression,
        name: String,
        scope: IrSymbol,
        parent: IrDeclarationParent,
    ): IrExpression {
        val builder = DeclarationIrBuilder(context, scope, call.startOffset, call.endOffset)
        val target = tracker.owner
        val resultType = call.type
        val substitutor = IrTypeSubstitutor(target.typeParameters.map { it.symbol }, listOf(resultType))
        val lambdaType = substitutor.substitute(target.parameters.last().type)
        val contentNameType = target.parameters[4].type
        val argumentsType = target.parameters[3].type
        val continuation = context.irFactory.buildFun {
            startOffset = call.startOffset
            endOffset = call.endOffset
            this.name = Name.special("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            returnType = resultType
        }.apply { this.parent = parent }
        val values = continuation.addValueParameter("values", argumentsType)
        val continuationBuilder = DeclarationIrBuilder(context, continuation.symbol, call.startOffset, call.endOffset)
        val entries = ArrayList<IrExpression>()
        val names = ArrayList<String>()
        val argumentTypes = argumentTypes(call)
        val function = call.symbol.owner
        val hasInlinedArgument = function.parameters.withIndex().any { (index, parameter) ->
            val argument = call.arguments[index]
            function.isInline && argument != null && !parameter.isNoinline &&
                (argument is IrFunctionExpression || parameter.type.isFunctionType())
        }
        // Inline lambda bodies have to stay at the original call. Avoid duplicating their IR.
        val direct = if (hasInlinedArgument) null else IrCallImpl(
            startOffset = call.startOffset,
            endOffset = call.endOffset,
            type = resultType,
            symbol = call.symbol,
            typeArgumentsCount = call.typeArguments.size,
            origin = call.origin,
            superQualifierSymbol = call.superQualifierSymbol,
        ).also { original ->
            for (index in call.typeArguments.indices) original.typeArguments[index] = call.typeArguments[index]
        }
        val slots = call.symbol.owner.parameters.withIndex().count { (index, parameter) ->
            parameter.kind == IrParameterKind.Regular && (
                parameter.type.hasAnnotation(composableAnnotation) ||
                    call.arguments[index]?.type?.hasAnnotation(composableAnnotation) == true ||
                    parameter.name.asString() in contentParameterNames && parameter.type.isFunctionType()
                )
        }
        return builder.irBlock(resultType = resultType) {
            var contentName: String? = null
            for ((index, parameter) in function.parameters.withIndex()) {
                val argument = call.arguments[index] ?: continue
                if (call.symbol.owner.isInline && !parameter.isNoinline &&
                    (argument is IrFunctionExpression || parameter.type.isFunctionType())
                ) continue

                val value = irTemporary(argument, "argument")
                direct?.let { it.arguments[index] = irGet(value) }
                if (parameter.kind != IrParameterKind.Regular) {
                    call.arguments[index] = irGet(value)
                    continue
                }
                val argumentType = argumentTypes[index] ?: argument.type
                val parameterName = parameter.name.asString()
                val valueIndex = entries.size
                names.add(parameterName)
                entries.add(irGet(value))
                call.arguments[index] = with(continuationBuilder) {
                    irCall(readArgument, argumentType).apply {
                        typeArguments[0] = argumentType
                        arguments[0] = irGet(values)
                        arguments[1] = irInt(valueIndex)
                    }
                }
                if (slots == 1 && argumentType is IrSimpleType &&
                    (argumentType.hasAnnotation(composableAnnotation) ||
                        argument.type.hasAnnotation(composableAnnotation) ||
                        parameter.type.hasAnnotation(composableAnnotation)) &&
                    argumentType.arguments.singleOrNull()?.typeOrNull?.isUnit() == true
                ) {
                    contentName = parameterName
                }
            }
            call.patchDeclarationParents(continuation)
            continuation.body = continuationBuilder.irBlockBody { +irReturn(call) }
            val tracked = irCall(tracker, resultType).apply {
                typeArguments[0] = resultType
                arguments[0] = site
                arguments[1] = irString(name)
                arguments[2] = irBoolean(resultType.isUnit())
                arguments[3] = irCall(createArguments).apply {
                    arguments[0] = irString(names.joinToString(separator = "\u0000"))
                    arguments[1] = irVararg(createArguments.owner.parameters[1].varargElementType!!, entries)
                }
                arguments[4] = contentName?.let { irString(it) } ?: irNull(contentNameType)
                arguments[5] = irBoolean(slots == 0 || contentName != null)
                arguments[6] = IrFunctionExpressionImpl(
                    call.startOffset, call.endOffset, lambdaType, continuation, IrStatementOrigin.LAMBDA,
                )
            }
            +(if (direct == null) tracked else irIfThenElse(resultType, irCall(isTrackingEnabled), tracked, direct))
        }
    }

    private fun IrType.isFunctionType(): Boolean {
        val name = (classifierOrNull as? IrClassSymbol)?.owner?.fqNameWhenAvailable?.asString().orEmpty()
        return name.startsWith("kotlin.Function") || name.startsWith("kotlin.coroutines.SuspendFunction")
    }

    private fun argumentTypes(call: IrCall): List<IrType?> {
        val function = call.symbol.owner
        val substitution = mutableMapOf<IrTypeParameterSymbol, IrTypeArgument>()
        for ((parameter, type) in function.typeParameters.zip(call.typeArguments)) {
            if (type != null) substitution[parameter.symbol] = type
        }
        val receiverType = call.dispatchReceiver?.type as? IrSimpleType
        val receiverClass = receiverType?.classifierOrNull as? IrClassSymbol
        if (receiverClass != null && receiverClass.owner == function.parent) {
            for ((parameter, type) in receiverClass.owner.typeParameters.zip(receiverType.arguments)) {
                if (type.typeOrNull != null) substitution[parameter.symbol] = type
            }
        }
        val substitutor = IrTypeSubstitutor(substitution, allowEmptySubstitution = true)
        fun isSubstitutable(type: IrType): Boolean = type !is IrSimpleType ||
            ((type.classifier as? IrTypeParameterSymbol)?.let { it in substitution } != false &&
                type.arguments.all { it.typeOrNull?.let(::isSubstitutable) != false })
        return function.parameters.map { parameter ->
            parameter.type.takeIf(::isSubstitutable)?.let(substitutor::substitute)
        }
    }

    companion object {
        private val contentParameterNames = setOf("content", "children")

        fun find(context: IrPluginContext, file: IrFile): GenericComposableCall? {
            val finder = context.finderForSource(file)
            val pkg = FqName("dev.composescopetree")
            fun findFunction(name: String) =
                finder.findFunctions(CallableId(pkg, Name.identifier(name))).singleOrNull()
            return GenericComposableCall(
                context,
                findFunction("TrackComposableCall") ?: return null,
                findFunction("implicitCallArguments") ?: return null,
                findFunction("implicitCallValue") ?: return null,
                findFunction("isImplicitScopeTrackingEnabled") ?: return null,
            )
        }
    }
}
