package org.hexworks.zircon.api.builder.component

import org.hexworks.zircon.api.component.NumberInput
import org.hexworks.zircon.api.data.Size
import org.hexworks.zircon.internal.component.impl.DefaultVerticalNumberInput
import org.hexworks.zircon.internal.component.renderer.DefaultVerticalNumberInputRenderer
import org.hexworks.zircon.internal.dsl.ZirconDsl
import kotlin.jvm.JvmStatic

@Suppress("UNCHECKED_CAST")
@ZirconDsl
class VerticalNumberInputBuilder private constructor() : NumberInputBuilder<NumberInput, VerticalNumberInputBuilder>(
    initialRenderer = DefaultVerticalNumberInputRenderer()
) {

    override fun calculateContentSize(): Size {
        val length = this.maxValue.toString().length + 1
        return if (contentWidth < length) {
            Size.create(1, length)
        } else preferredContentSize
    }

    override fun build(): NumberInput = DefaultVerticalNumberInput(
        componentMetadata = createMetadata(),
        renderingStrategy = createRenderingStrategy(),
        initialValue = initialValue,
        minValue = minValue,
        maxValue = maxValue,
    ).attachListeners()

    override fun createCopy() = newBuilder()
        .withProps(props.copy())
        .withInitialValue(initialValue)
        .withMinValue(minValue)
        .withMaxValue(maxValue)

    companion object {

        @JvmStatic
        fun newBuilder() = VerticalNumberInputBuilder()
    }
}
