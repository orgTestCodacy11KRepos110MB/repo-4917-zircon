package org.hexworks.zircon.internal.renderer

import org.hexworks.cobalt.databinding.api.extension.toProperty
import org.hexworks.cobalt.databinding.api.property.Property
import org.hexworks.cobalt.events.api.Subscription
import org.hexworks.zircon.api.application.AppConfig
import org.hexworks.zircon.api.application.Application
import org.hexworks.zircon.api.application.CloseBehavior
import org.hexworks.zircon.api.application.CursorStyle
import org.hexworks.zircon.api.behavior.TilesetHolder
import org.hexworks.zircon.api.data.CharacterTile
import org.hexworks.zircon.api.data.Position
import org.hexworks.zircon.api.data.StackedTile
import org.hexworks.zircon.api.data.Tile
import org.hexworks.zircon.api.graphics.TileGraphics
import org.hexworks.zircon.api.modifier.TileTransformModifier
import org.hexworks.zircon.api.resource.TilesetResource
import org.hexworks.zircon.api.tileset.Tileset
import org.hexworks.zircon.api.tileset.TilesetLoader
import org.hexworks.zircon.internal.behavior.Observable
import org.hexworks.zircon.internal.behavior.impl.DefaultObservable
import org.hexworks.zircon.internal.graphics.FastTileGraphics
import org.hexworks.zircon.internal.grid.InternalTileGrid
import org.hexworks.zircon.internal.impl.SwingFrame
import org.hexworks.zircon.internal.tileset.transformer.toAWTColor
import org.hexworks.zircon.internal.uievent.KeyboardEventListener
import org.hexworks.zircon.internal.uievent.MouseEventListener
import org.hexworks.zircon.platform.util.SystemUtils
import java.awt.*
import java.awt.event.HierarchyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferStrategy
import javax.swing.JFrame

@Suppress("UNCHECKED_CAST")
class SwingCanvasRenderer private constructor(
    private val tileGrid: InternalTileGrid,
    private val tilesetLoader: TilesetLoader<Graphics2D>,
    private val canvas: Canvas = Canvas(),
    private val frame: JFrame = SwingFrame(
        tileGrid = tileGrid,
        canvas = canvas
    ),
) : Renderer, Observable<SwingCanvasRenderer> by DefaultObservable() {

    override val closedValue: Property<Boolean> = false.toProperty()

    private var blinkOn = true
    private var lastRender: Long = SystemUtils.getCurrentTimeMs()
    private var lastBlink: Long = lastRender

    private val config = tileGrid.config
    private val keyboardEventListener = KeyboardEventListener()
    private val mouseEventListener = object : MouseEventListener(
        fontWidth = tileGrid.tileset.width,
        fontHeight = tileGrid.tileset.height
    ) {
        override fun mouseClicked(e: MouseEvent) {
            super.mouseClicked(e)
            canvas.requestFocusInWindow()
        }
    }

    private val gridPositions = tileGrid.size.fetchPositions().toList()

    /**
     * Adds a callback [fn] that will be called whenever the frame where the contents
     * of the [tileGrid] are rendered is closed.
     */
    fun onFrameClosed(fn: (SwingCanvasRenderer) -> Unit): Subscription {
        return addObserver(fn)
    }

    override fun create() {
        if (closed.not()) {
            // display settings
            if (config.fullScreen) {
                frame.extendedState = JFrame.MAXIMIZED_BOTH
            }
            if (config.borderless) {
                frame.isUndecorated = true
            }

            // no resize
            frame.isResizable = false

            // rendering
            frame.addWindowStateListener {
                if (it.newState == Frame.NORMAL) {
                    render()
                }
            }

            // dimensions
            canvas.preferredSize = Dimension(
                tileGrid.widthInPixels,
                tileGrid.heightInPixels
            )
            canvas.minimumSize = Dimension(tileGrid.tileset.width, tileGrid.tileset.height)

            // input listeners
            canvas.addKeyListener(keyboardEventListener)
            canvas.addMouseListener(mouseEventListener)
            canvas.addMouseMotionListener(mouseEventListener)
            canvas.addMouseWheelListener(mouseEventListener)

            // window closed
            canvas.addHierarchyListener { e ->
                if (e.changeFlags == HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) {
                    if (!e.changed.isDisplayable) {
                        close()
                    }
                }
            }

            // close behavior
            frame.defaultCloseOperation = when (config.closeBehavior) {
                CloseBehavior.DO_NOTHING_ON_CLOSE -> JFrame.DO_NOTHING_ON_CLOSE
                CloseBehavior.EXIT_ON_CLOSE -> JFrame.EXIT_ON_CLOSE
            }

            // app stop callback
            frame.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(windowEvent: WindowEvent?) {
                    notifyObservers(this@SwingCanvasRenderer)
                }
            })

            // focus settings
            canvas.isFocusable = true
            canvas.requestFocusInWindow()
            canvas.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet<AWTKeyStroke>())
            canvas.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet<AWTKeyStroke>())

            // display
            frame.pack()
            frame.isVisible = true
            frame.setLocationRelativeTo(null)

            // buffering
            canvas.createBufferStrategy(2)
            initializeBufferStrategy()
        }
    }

    override fun render() {
        if (closed.not()) {
            val now = SystemUtils.getCurrentTimeMs()

            processInputEvents()
            tileGrid.updateAnimations(now, tileGrid)

            val bs: BufferStrategy = canvas.bufferStrategy // this is a regular Swing Canvas object
            handleBlink(now)

            canvas.bufferStrategy.drawGraphics.configure().apply {
                color = Color.BLACK
                fillRect(0, 0, tileGrid.widthInPixels, tileGrid.heightInPixels)
                drawTiles(this)
                if (shouldDrawCursor()) {
                    val tile = tileGrid.getTileAtOrElse(tileGrid.cursorPosition) { Tile.empty() }
                    drawCursor(this, tile, tileGrid.cursorPosition)
                }
                dispose()
            }

            bs.show()
            lastRender = now
        }
    }

    override fun close() {
        if (!closed) {
            closedValue.value = true
            tileGrid.close()
            frame.dispose()
        }
    }

    private fun drawTiles(graphics: Graphics2D) {
        val layers = fetchLayers()
        val tiles = mutableListOf<Pair<Tile, TilesetResource>>()
        gridPositions.forEach { pos ->
            tiles@ for (i in layers.size - 1 downTo 0) {
                val (layerPos, layer) = layers[i]
                val toRender = layer.getTileAtOrNull(pos - layerPos)?.tiles() ?: listOf()
                for (j in toRender.size - 1 downTo 0) {
                    val tile = toRender[j]
                    val tileset = tile.finalTileset(layer)
                    tiles.add(0, tile to tileset)
                    if (tile.isOpaque) {
                        break@tiles
                    }
                }

            }
            for ((tile, tileset) in tiles) {
                renderTile(
                    graphics = graphics,
                    position = pos,
                    tile = tile,
                    tileset = tilesetLoader.loadTilesetFrom(tileset)
                )
            }
            tiles.clear()
        }
    }

    private fun Tile.tiles(): List<Tile> = if (this is StackedTile) {
        tiles.flatMap { it.tiles() }
    } else listOf(this)

    private fun fetchLayers(): List<Pair<Position, TileGraphics>> {
        return tileGrid.renderables.map { renderable ->
            val tg = FastTileGraphics(
                initialSize = renderable.size,
                initialTileset = renderable.tileset,
            )
            if (!renderable.isHidden) {
                renderable.render(tg)
            }
            renderable.position to tg
        }
    }

    private fun Graphics.configure(): Graphics2D {
        val gc = this as Graphics2D
        gc.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        gc.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
        gc.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE)
        gc.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
        gc.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
        gc.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED)
        gc.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
        return gc
    }

    private fun renderTile(
        graphics: Graphics2D,
        position: Position,
        tile: Tile,
        tileset: Tileset<Graphics2D>
    ) {
        if (tile.isNotEmpty) {
            var finalTile = tile
            finalTile.modifiers.filterIsInstance<TileTransformModifier<CharacterTile>>().forEach { modifier ->
                if (modifier.canTransform(finalTile)) {
                    (finalTile as? CharacterTile)?.let {
                        finalTile = modifier.transform(it)
                    }
                }
            }
            finalTile = if (tile.isBlinking && blinkOn) {
                tile.withBackgroundColor(tile.foregroundColor)
                    .withForegroundColor(tile.backgroundColor)
            } else {
                tile
            }
            ((finalTile as? TilesetHolder)?.let {
                tilesetLoader.loadTilesetFrom(it.tileset)
            } ?: tileset).drawTile(
                tile = finalTile,
                surface = graphics,
                position = position
            )
        }
    }

    private fun processInputEvents() {
        keyboardEventListener.drainEvents().forEach { (event, phase) ->
            tileGrid.process(event, phase)
        }
        mouseEventListener.drainEvents().forEach { (event, phase) ->
            tileGrid.process(event, phase)
        }
    }

    private fun handleBlink(now: Long) {
        if (now > lastBlink + config.blinkLengthInMilliSeconds) {
            blinkOn = !blinkOn
            lastBlink = now
        }
    }

    private fun drawCursor(graphics: Graphics, character: Tile, position: Position) {
        val tileWidth = tileGrid.tileset.width
        val tileHeight = tileGrid.tileset.height
        val x = position.x * tileWidth
        val y = position.y * tileHeight
        val cursorColor = config.cursorColor.toAWTColor()
        graphics.color = cursorColor
        when (config.cursorStyle) {
            CursorStyle.USE_CHARACTER_FOREGROUND -> {
                if (blinkOn) {
                    graphics.color = character.foregroundColor.toAWTColor()
                    graphics.fillRect(x, y, tileWidth, tileHeight)
                }
            }
            CursorStyle.FIXED_BACKGROUND -> graphics.fillRect(x, y, tileWidth, tileHeight)
            CursorStyle.UNDER_BAR -> graphics.fillRect(x, y + tileHeight - 3, tileWidth, 2)
            CursorStyle.VERTICAL_BAR -> graphics.fillRect(x, y + 1, 2, tileHeight - 2)
        }
    }

    private fun shouldDrawCursor(): Boolean {
        return tileGrid.isCursorVisible &&
                (config.isCursorBlinking.not() || config.isCursorBlinking && blinkOn)
    }

    private tailrec fun initializeBufferStrategy() {
        val bs = canvas.bufferStrategy
        var failed = false
        try {
            bs.drawGraphics as Graphics2D
        } catch (e: NullPointerException) {
            failed = true
        }
        if (failed) {
            initializeBufferStrategy()
        }
    }

    companion object {

        fun create(
            tileGrid: InternalTileGrid,
            tilesetLoader: TilesetLoader<Graphics2D>,
            canvas: Canvas = Canvas(),
        ): SwingCanvasRenderer = SwingCanvasRenderer(
            tileGrid = tileGrid,
            tilesetLoader = tilesetLoader,
            canvas = canvas
        )
    }
}

private fun Tile.finalTileset(graphics: TileGraphics): TilesetResource {
    return if (this is TilesetHolder) {
        tileset
    } else graphics.tileset
}
