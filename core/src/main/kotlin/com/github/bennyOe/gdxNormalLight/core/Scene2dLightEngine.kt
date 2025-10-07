package com.github.bennyOe.gdxNormalLight.core

import box2dLight.RayHandler
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.utils.viewport.Viewport
import com.github.bennyOe.gdxNormalLight.scene2d.NormalMappedActor
import ktx.math.vec2
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A specialized light engine for Scene2D applications, combining normal mapping shaders with Box2D shadow rendering.
 *
 * This engine simplifies integration of dynamic lighting into Scene2D-based games and UI using a consistent rendering pipeline.
 * It supports diffuse, normal, and specular mapping and is designed to be used with a Scene2D [Stage] and [Actor]s.
 *
 * It provides convenience methods to draw Scene2D actors or texture regions using lighting, while managing shader state,
 * texture unit bindings, and batching automatically.
 *
 * @param rayHandler The Box2D RayHandler instance used for shadow rendering.
 * @param cam The camera used to render the Scene2D stage.
 * @param batch The SpriteBatch used to draw Scene2D actors and textures.
 * @param viewport The viewport used to project the stage and shadow rendering.
 * @param stage The Scene2D stage containing actors. May be null if rendering is handled manually.
 * @param lightViewportScale Multiplier for how much wider/taller the shadow pass covers compared to the visible viewport. Typical values: 1–4.
 * @param useDiffuseLight Whether to apply diffuse light shading in the lighting shader.
 * @param maxShaderLights Maximum number of shader-based lights supported by the engine.
 * @param entityCategory Optional: Bitmask defining the category of lights created through this engine.
 * @param entityMask Optional: Bitmask defining the collision mask for lights created through this engine.
 * @param lightActivationRadius The maximum distance from the center within which lights are activated. Use -1 to disable the radius limit.
 */
class Scene2dLightEngine(
    rayHandler: RayHandler,
    cam: OrthographicCamera,
    batch: SpriteBatch,
    viewport: Viewport,
    val stage: Stage?,
    lightViewportScale: Float = 2f,
    useDiffuseLight: Boolean = true,
    maxShaderLights: Int = 32,
    entityCategory: Short = 0x0001,
    entityMask: Short = -1,
    lightActivationRadius: Float = -1f,
    refreshRateHz: Float? = null,
) : AbstractLightEngine(
    rayHandler,
    cam,
    batch,
    viewport,
    useDiffuseLight,
    maxShaderLights,
    entityCategory,
    entityMask,
    lightActivationRadius,
    lightViewportScale,
    refreshRateHz,
) {
    /**
     * Performs the complete lighting render pass using normal mapping and Box2D shadows.
     *
     * This function sets up the shader, uploads all light properties, invokes a user-provided lambda to render the
     * scene with lighting, and then renders Box2D-based shadows on top. It must be called once per frame.
     *
     * ### What this function does:
     * - Configures the batch with the shader and camera matrix.
     * - Applies lighting-related uniforms to the shader (light count, color, falloff, direction, etc.).
     * - Calls the [drawScene] lambda where you render all visible objects using your own draw logic.
     * - Renders Box2D shadows via [RayHandler].
     *
     * ### Requirements inside [drawScene]:
     * - **Normal map must be bound to texture unit 1** before calling `batch.draw(...)`.
     * - **Diffuse texture must be bound to texture unit 0** before calling `batch.draw(...)`.
     * - Use the batch normally for rendering your sprites — lighting will be automatically applied by the shader.
     *
     * @param center The [Actor] used as the center point for light culling and focus (usually the camera or player).
     * @param drawScene Lambda in which your game scene should be rendered with lighting applied.
     */
    fun renderLights(
        center: Actor = Actor(),
        drawScene: (Scene2dLightEngine) -> Unit,
    ) {
        renderSceneWithShader(center, drawScene)
        renderBox2dLights()
    }

    /**
     * Renders the scene using the engine's lighting shader.
     *
     * Sets up the batch with the camera and viewport, updates active lights based on the given center actor,
     * applies all lighting-related shader uniforms, and executes the provided [drawScene] lambda for custom drawing.
     * This method should be called within the main render loop to ensure correct lighting and shader state.
     *
     * @param center The [Actor] used as the focus point for light culling and shader calculations.
     * @param drawScene Lambda where the scene's objects should be drawn using the batch.
     */
    fun renderSceneWithShader(
        center: Actor = Actor(),
        drawScene: (Scene2dLightEngine) -> Unit,
    ) {
        batch.projectionMatrix = cam.combined
        viewport.apply()

        val centerX = center.x + center.width * .5f
        val centerY = center.y + center.height * .5f
        updateActiveLights(vec2(centerX, centerY))

        setShaderToEngineShader()
        applyShaderUniforms()
        batch.begin()
        lastNormalMap = null
        lastSpecularMap = null
        drawScene(this)
        batch.end()
        setShaderToDefaultShader()
    }

    /**
     * Draws a sprite using both a diffuse and a normal map texture, applying normal mapping lighting effects.
     *
     * This method binds the normal map to texture unit 1 and the diffuse texture to unit 0, sets the appropriate
     * shader uniforms, and draws the sprite at the specified position and size. If `flipX` is true, the sprite
     * is drawn mirrored horizontally.
     *
     * Use this method within the [renderLights] lambda to ensure lighting and shader context are active.
     *
     * @param diffuse The diffuse [TextureRegion] (base color texture).
     * @param normals The normal map [TextureRegion] (for lighting effects).
     * @param x The x-coordinate to draw the sprite.
     * @param y The y-coordinate to draw the sprite.
     * @param width The width of the sprite.
     * @param height The height of the sprite.
     * @param flipX If true, the sprite is drawn mirrored on the X axis.
     */
    fun draw(
        diffuse: TextureRegion,
        normals: TextureRegion,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        flipX: Boolean = false,
    ) {
        if (lastNormalMap == null || normals.texture != lastNormalMap) {
            batch.flush()
        }
        shader.setUniformi("u_useNormalMap", 1)
        shader.setUniformi("u_useSpecularMap", 0)

        shader.setUniformi("u_flipX", if (flipX) 1 else 0)

        normals.texture.bind(1)
        diffuse.texture.bind(0)

        if (flipX) {
            batch.draw(diffuse, x + width, y, -width, height)
        } else {
            batch.draw(diffuse, x, y, width, height)
        }
        lastNormalMap = normals.texture
        lastSpecularMap = null
    }

    /**
     * Draws a sprite using a diffuse, a normal and a specular map texture, applying normal mapping and specular lighting effects.
     *
     * This method binds the normal map to texture unit 1, the specular map to texture unit 2 and the diffuse texture to unit 0, sets the appropriate
     * shader uniforms, and draws the sprite at the specified position and size. If `flipX` is true, the sprite
     * is drawn mirrored horizontally.
     *
     * Use this method within the [renderLights] lambda to ensure lighting and shader context are active.
     *
     * @param diffuse The diffuse [TextureRegion] (base color texture).
     * @param normals The normal map [TextureRegion] (for lighting effects).
     * @param specular The specular map [TextureRegion] (for highlight effects).
     * @param x The x-coordinate to draw the sprite.
     * @param y The y-coordinate to draw the sprite.
     * @param width The width of the sprite.
     * @param height The height of the sprite.
     * @param flipX If true, the sprite is drawn mirrored on the X axis.
     */
    fun draw(
        diffuse: TextureRegion,
        normals: TextureRegion,
        specular: TextureRegion,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        flipX: Boolean = false,
    ) {
        if (lastNormalMap == null || normals.texture != lastNormalMap) {
            batch.flush()
        }
        if (lastSpecularMap == null || specular.texture != lastSpecularMap) {
            batch.flush()
        }
        shader.setUniformi("u_useNormalMap", 1)
        shader.setUniformi("u_useSpecularMap", 1)

        shader.setUniformi("u_flipX", if (flipX) 1 else 0)

        normals.texture.bind(1)
        specular.texture.bind(2)
        diffuse.texture.bind(0)

        if (flipX) {
            batch.draw(diffuse, x + width, y, -width, height)
        } else {
            batch.draw(diffuse, x, y, width, height)
        }
        lastNormalMap = normals.texture
        lastSpecularMap = specular.texture
    }

    /**
     * Draws a Scene2D [Image] without applying normal mapping.
     *
     * This method disables normal mapping in the shader, flushes the batch if a normal map was previously used,
     * and renders the given [Image] using the lighting shader with normal mapping turned off.
     *
     * Use this when rendering images that do not have an associated normal map, ensuring correct lighting behavior.
     *
     * @param image The [Image] to render without normal mapping.
     */
    fun drawWithoutNormalMap(image: Image) {
        // If we were just drawing with a normal map, flush the batch before changing the shader state.
        if (lastNormalMap != null) {
            batch.flush()
        }

        // Set our lighting shader and tell it to ignore the normal map uniform.
        batch.shader = this.shader
        shader.bind()
        shader.setUniformi("u_useNormalMap", 0)
        // Draw the image normally
        image.draw(batch, 1f)

        // Update the state to reflect that we are no longer using a normal map.
        lastNormalMap = null
    }

    /**
     * Draws a Scene2D [Actor] with optional normal mapping.
     *
     * If the actor is a [NormalMappedActor], it binds the normal map to texture unit 1
     * and the diffuse texture to unit 0, and renders it with lighting effects applied.
     * If the actor is not normal-mapped, it disables normal mapping in the shader and
     * renders the actor using the standard [Actor.draw] method.
     *
     * This method must be called **within** the [renderLights] lambda to ensure lighting
     * uniforms and shader context are active.
     *
     * @param actor The [Actor] to render. May or may not have an associated normal map.
     */
    fun draw(actor: Actor) {
        // Determine if the actor is flipped horizontally.
        val flipX = actor.scaleX < 0

        shader.bind()
        shader.setUniformi("u_flipX", if (flipX) 1 else 0)

        if (actor is NormalMappedActor) {
            if (lastNormalMap == null || lastNormalMap != actor.normalMapTexture) {
                batch.flush()
            }

            shader.setUniformi("u_useNormalMap", if (actor.normalMapTexture != null) 1 else 0)
            shader.setUniformi("u_useSpecularMap", if (actor.specularTexture != null) 1 else 0)

            actor.normalMapTexture?.bind(1)
            lastNormalMap = actor.normalMapTexture

            actor.specularTexture?.bind(2)
            lastSpecularMap = actor.specularTexture

            actor.diffuseTexture.bind(0)
            batch.draw(actor.diffuseTexture, actor.x, actor.y, actor.width, actor.height)
        } else {
            if (lastNormalMap != null || lastSpecularMap != null) {
                batch.flush()
            }
            shader.setUniformi("u_useNormalMap", 0)
            shader.setUniformi("u_useSpecularMap", 0)

            actor.draw(batch, 1.0f)

            lastNormalMap = null
            lastSpecularMap = null
        }
    }

    /**
     * Draws a tiled region using a diffuse and normal map, applying normal mapping lighting effects.
     *
     * This method binds the normal map to texture unit 1 and the diffuse texture to unit 0, sets the appropriate
     * shader uniforms, and tiles the region over the specified area. If `flipX` is true, the tiles are mirrored horizontally.
     *
     * Use this method within the [renderLights] lambda to ensure lighting and shader context are active.
     *
     * @param diffuse The diffuse [TextureRegion] (base color texture).
     * @param normals The normal map [TextureRegion] (for lighting effects).
     * @param x The x-coordinate of the area to tile.
     * @param y The y-coordinate of the area to tile.
     * @param width The width of the area to tile.
     * @param height The height of the area to tile.
     * @param flipX If true, tiles are mirrored on the X axis.
     * @param scale The scale factor for each tile (default is 1.0).
     * @param unitScale The scale factor to convert texture pixels to world units (default is 1.0).
     */
    fun drawTiled(
        diffuse: TextureRegion,
        normals: TextureRegion,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        flipX: Boolean = false,
        scale: Float = 1.0f,
        unitScale: Float = 1.0f,
    ) {
        if (lastNormalMap == null || normals.texture != lastNormalMap) {
            batch.flush()
        }
        shader.bind()
        shader.setUniformi("u_useNormalMap", 1)
        shader.setUniformi("u_useSpecularMap", 0)
        shader.setUniformi("u_flipX", if (flipX) 1 else 0)

        normals.texture.bind(1)
        diffuse.texture.bind(0)

        drawTiledRegion(diffuse, x, y, width, height, scale, unitScale)

        lastNormalMap = normals.texture
        lastSpecularMap = null
    }

    /**
     * Draws a tiled region using diffuse, normal, and specular maps, applying normal mapping and specular lighting effects.
     *
     * This method binds the normal map to texture unit 1, the specular map to texture unit 2, and the diffuse texture to unit 0.
     * It sets the appropriate shader uniforms and tiles the region over the specified area. If `flipX` is true, the tiles are mirrored horizontally.
     *
     * Use this method within the [renderLights] lambda to ensure lighting and shader context are active.
     *
     * @param diffuse The diffuse [TextureRegion] (base color texture).
     * @param normals The normal map [TextureRegion] (for lighting effects).
     * @param specular The specular map [TextureRegion] (for highlight effects).
     * @param x The x-coordinate of the area to tile.
     * @param y The y-coordinate of the area to tile.
     * @param width The width of the area to tile.
     * @param height The height of the area to tile.
     * @param flipX If true, tiles are mirrored on the X axis.
     * @param scale The scale factor for each tile (default is 1.0).
     * @param unitScale The scale factor to convert texture pixels to world units (default is 1.0).
     */
    fun drawTiled(
        diffuse: TextureRegion,
        normals: TextureRegion,
        specular: TextureRegion,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        flipX: Boolean = false,
        scale: Float = 1.0f,
        unitScale: Float = 1.0f,
    ) {
        if (lastNormalMap == null || normals.texture != lastNormalMap) {
            batch.flush()
        }
        if (lastSpecularMap == null || specular.texture != lastSpecularMap) {
            batch.flush()
        }
        shader.bind()
        shader.setUniformi("u_useNormalMap", 1)
        shader.setUniformi("u_useSpecularMap", 1)
        shader.setUniformi("u_flipX", if (flipX) 1 else 0)

        normals.texture.bind(1)
        specular.texture.bind(2)
        diffuse.texture.bind(0)

        drawTiledRegion(diffuse, x, y, width, height, scale, unitScale)

        lastNormalMap = normals.texture
        lastSpecularMap = specular.texture
    }

    /**
     * Tiles a given [TextureRegion] over a specified area, drawing only the visible intersections.
     *
     * This function calculates the tile size in world units (using [unitScale] and [scale]), determines
     * which tiles intersect the target area, and draws each tile with the correct source and destination
     * coordinates. Handles partial tiles at the edges and supports arbitrary scaling.
     *
     * @param region The [TextureRegion] to tile.
     * @param x The x-coordinate of the area to fill (world units).
     * @param y The y-coordinate of the area to fill (world units).
     * @param width The width of the area to fill (world units).
     * @param height The height of the area to fill (world units).
     * @param scale The scale factor for each tile (default is 1.0).
     * @param unitScale The scale factor to convert texture pixels to world units (default is 1.0).
     */
    private fun drawTiledRegion(
        region: TextureRegion,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        scale: Float = 1.0f,
        unitScale: Float = 1.0f,
    ) {
        val tileWidthWU = region.regionWidth * unitScale * scale
        val tileHeightWU = region.regionHeight * unitScale * scale
        if (tileWidthWU <= 0 || tileHeightWU <= 0) return

        // Calculate the range of columns and rows to draw.
        val startCol = floor(x / tileWidthWU).toInt()
        val endCol = ceil((x + width) / tileWidthWU).toInt()
        val startRow = floor(y / tileHeightWU).toInt()
        val endRow = ceil((y + height) / tileHeightWU).toInt()

        for (row in startRow until endRow) {
            for (col in startCol until endCol) {
                val tileX = col * tileWidthWU
                val tileY = row * tileHeightWU

                // Calculate the intersection of this tile with the drawing area.
                val drawX = maxOf(x, tileX)
                val drawY = maxOf(y, tileY)
                val intersectionWidth = minOf(x + width, tileX + tileWidthWU) - drawX
                val intersectionHeight = minOf(y + height, tileY + tileHeightWU) - drawY

                if (intersectionWidth <= 0 || intersectionHeight <= 0) continue

                // Calculate the source region from the texture atlas.
                val srcX = (drawX - tileX) / tileWidthWU * region.regionWidth
                val srcY = (drawY - tileY) / tileHeightWU * region.regionHeight
                val srcWidth = intersectionWidth / tileWidthWU * region.regionWidth
                val srcHeight = intersectionHeight / tileHeightWU * region.regionHeight

                batch.draw(
                    region.texture,
                    drawX,
                    drawY,
                    intersectionWidth,
                    intersectionHeight,
                    (region.regionX + srcX).toInt(),
                    (region.regionY + srcY).toInt(),
                    srcWidth.toInt(),
                    srcHeight.toInt(),
                    false,
                    false,
                )
            }
        }
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        if (stage == null) return
        stage.viewport.update(width, height, true)
        val screenX = stage.viewport.screenX * Gdx.graphics.backBufferScale.toInt()
        val screenY = stage.viewport.screenY * Gdx.graphics.backBufferScale.toInt()
        val screenW = stage.viewport.screenWidth * Gdx.graphics.backBufferScale.toInt()
        val screenH = stage.viewport.screenHeight * Gdx.graphics.backBufferScale.toInt()
        rayHandler.useCustomViewport(screenX, screenY, screenW, screenH)
        super.resize(width, height)
    }
}
