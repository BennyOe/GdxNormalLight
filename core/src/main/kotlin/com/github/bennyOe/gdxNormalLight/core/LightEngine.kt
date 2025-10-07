package com.github.bennyOe.gdxNormalLight.core

import box2dLight.RayHandler
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.Viewport
import ktx.math.vec2

/**
 * A lighting engine that integrates normal mapping and Box2D-based shadow rendering into a 2D scene.
 *
 * This engine combines shader-based lighting (including support for diffuse, normal, and specular maps)
 * with real-time shadows provided by box2dLight. It simplifies the process of rendering fully lit scenes
 * using a single `renderLights { ... }` entry point.
 *
 * It offers helper methods like [draw] to render sprites with different map combinations and manages
 * shader bindings and texture unit assignments automatically.
 *
 * @param rayHandler The Box2D RayHandler instance used to render physical light shadows.
 * @param cam The camera used to render the scene.
 * @param batch The SpriteBatch used for rendering the scene with lighting.
 * @param viewport The Viewport used to configure the screen projection.
 * @param lightViewportScale Multiplier for how much wider/taller the shadow pass covers compared to the visible viewport. Typical values: 1–4.
 * @param useDiffuseLight Whether to apply diffuse lighting calculation in the shader.
 * @param maxShaderLights Maximum number of shader-based lights supported.
 * @param entityCategory Optional: Bitmask defining the category of lights created through this engine.
 * @param entityMask Optional: Bitmask defining the collision mask for lights created through this engine.
 * @param lightActivationRadius The maximum distance from the center within which lights are activated. Use -1 to disable the radius limit.
 */
class LightEngine(
    rayHandler: RayHandler,
    cam: OrthographicCamera,
    batch: SpriteBatch,
    viewport: Viewport,
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
     * @param center The world position around which lights are prioritized and updated.
     * @param drawScene Lambda in which your game scene should be rendered with lighting applied.
     */
    fun renderLights(
        center: Vector2 = vec2(0f, 0f),
        drawScene: (LightEngine) -> Unit,
    ) {
        renderSceneWithShader(center, drawScene)
        renderBox2dLights()
    }

    /**
     * Renders the scene using the engine's lighting shader.
     *
     * This method sets up the batch with the correct projection matrix and applies the viewport.
     * It updates the active lights based on the given center position, binds the engine's lighting shader,
     * and uploads all relevant uniforms. The provided [drawScene] lambda is then invoked, allowing you to
     * render your scene with lighting effects applied. After rendering, the batch is ended and the default
     * shader is restored.
     *
     * @param center The world position around which lights are prioritized and updated.
     * @param drawScene Lambda in which your game scene should be rendered with lighting applied.
     */
    fun renderSceneWithShader(
        center: Vector2 = vec2(0f, 0f),
        drawScene: (LightEngine) -> Unit,
    ) {
        batch.projectionMatrix = cam.combined
        viewport.apply()

        updateActiveLights(center)
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
     * Draws a textured quad with a diffuse, a normal and a specular map, binding them to the correct texture units.
     *
     * This method binds the provided diffuse texture to texture unit 0, the normal map to texture unit 1 and the specular map to texture unit 2,
     * ensuring they are properly used by the lighting shader. It should only be called within the [renderLights] lambda.
     *
     * If the normal map or specular map differs from the previously used one, the batch is flushed to prevent texture conflicts.
     *
     * @param diffuse The diffuse texture (base color).
     * @param normals The corresponding normal map texture.
     * @param specular The corresponding specular map texture.
     * @param x The x-position in world coordinates.
     * @param y The y-position in world coordinates.
     * @param width The width of the quad to draw.
     * @param height The height of the quad to draw.
     * @param flipX If true, the sprite is drawn mirrored on the X axis.
     */
    fun draw(
        diffuse: Texture,
        normals: Texture,
        specular: Texture,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        flipX: Boolean = false,
    ) {
        if (lastNormalMap == null || normals != lastNormalMap) {
            batch.flush()
        }
        shader.bind()
        shader.setUniformi("u_useNormalMap", 1)
        shader.setUniformi("u_useSpecularMap", 1)
        shader.setUniformi("u_flipX", if (flipX) 1 else 0)

        if (lastSpecularMap == null || specular != lastSpecularMap) {
            batch.flush()
        }

        normals.bind(1)
        specular.bind(2)
        diffuse.bind(0)
        if (flipX) {
            batch.draw(diffuse, x + width, y, -width, height)
        } else {
            batch.draw(diffuse, x, y, width, height)
        }
        lastNormalMap = normals
        lastSpecularMap = specular
    }

    /**
     * Draws a textured quad with a diffuse and normal map, binding them to the correct texture units.
     *
     * This method binds the provided diffuse texture to texture unit 0 and the normal map to texture unit 1,
     * ensuring they are properly used by the lighting shader. It should only be called within the [renderLights] lambda.
     *
     * If the normal map differs from the previously used one, the batch is flushed to prevent texture conflicts.
     *
     * @param diffuse The diffuse texture (base color).
     * @param normals The corresponding normal map texture.
     * @param x The x-position in world coordinates.
     * @param y The y-position in world coordinates.
     * @param width The width of the quad to draw.
     * @param height The height of the quad to draw.
     * @param flipX If true, the sprite is drawn mirrored on the X axis.
     */
    fun draw(
        diffuse: Texture,
        normals: Texture,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        flipX: Boolean = false,
    ) {
        if (lastNormalMap == null || normals != lastNormalMap) {
            batch.flush()
        }
        shader.bind()
        shader.setUniformi("u_useNormalMap", 1)
        shader.setUniformi("u_useSpecularMap", 0)
        shader.setUniformi("u_flipX", if (flipX) 1 else 0)

        normals.bind(1)
        diffuse.bind(0)
        if (flipX) {
            batch.draw(diffuse, x + width, y, -width, height)
        } else {
            batch.draw(diffuse, x, y, width, height)
        }
        lastNormalMap = normals
        lastSpecularMap = null
    }

    /**
     * Draws a textured quad using only a diffuse texture, without normal mapping.
     *
     * This method disables normal mapping by informing the shader to ignore the normal map.
     * It is useful for objects that do not require dynamic lighting effects, such as UI elements,
     * background layers, or sprites meant to remain unaffected by lighting.
     *
     * This function must also be called within the [renderLights] lambda to ensure the shader context is valid.
     *
     * @param diffuse The diffuse texture (base color).
     * @param x The x-position in world coordinates.
     * @param y The y-position in world coordinates.
     * @param width The width of the quad to draw.
     * @param height The height of the quad to draw.
     * @param flipX If true, the sprite is drawn mirrored on the X axis.
     */
    fun draw(
        diffuse: Texture,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        flipX: Boolean = false,
    ) {
        if (lastNormalMap != null) {
            batch.flush()
        }

        shader.bind()
        shader.setUniformi("u_useNormalMap", 0)
        shader.setUniformi("u_useSpecularMap", 0)
        shader.setUniformi("u_flipX", if (flipX) 1 else 0)

        diffuse.bind(0)
        if (flipX) {
            batch.draw(diffuse, x + width, y, -width, height)
        } else {
            batch.draw(diffuse, x, y, width, height)
        }
        lastNormalMap = null
        lastSpecularMap = null
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        super.resize(width, height)
    }
}
