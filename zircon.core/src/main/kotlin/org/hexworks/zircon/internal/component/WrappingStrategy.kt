package org.hexworks.zircon.internal.component

import org.hexworks.zircon.api.data.Position
import org.hexworks.zircon.api.data.Size
import org.hexworks.zircon.api.graphics.StyleSet
import org.hexworks.zircon.api.graphics.TileGraphic

interface WrappingStrategy {

    /**
     * Returns the size the wrapping occupies.
     * For example a border will occupy 2x2 size.
     */
    fun getOccupiedSize(): Size

    /**
     * Tells how many positions this wrapper
     * offsets the component. For example the default button
     * wrapper offsets the button with (1, 0).
     */
    fun getOffset(): Position

    /**
     * Applies the [WrappingStrategy] to a [TileGraphic] at the given offset (position)
     * and in a given [Size] using the given style. The offset/size is necessary because
     * if you apply border and shadow as well you need to know where the border should
     * end and the shadow should start.
     */
    fun apply(tileGraphic: TileGraphic, size: Size, offset: Position, style: StyleSet)

}
