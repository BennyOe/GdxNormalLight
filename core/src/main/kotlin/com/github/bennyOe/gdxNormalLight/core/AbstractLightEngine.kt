package com.github.bennyOe.gdxNormalLight.core

import box2dLight.ConeLight
import box2dLight.DirectionalLight
import box2dLight.PointLight
import box2dLight.RayHandler
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.physics.box2d.Filter
import com.badlogic.gdx.utils.GdxRuntimeException
import com.badlogic.gdx.utils.viewport.Viewport
import com.github.bennyOe.gdxNormalLight.core.utils.worldToScreenSpace
import ktx.assets.disposeSafely
import ktx.math.vec3
import ktx.math.vec4
import kotlin.math.cos
import kotlin.math.sin

/**
 * Abstract base class for 2D lighting engines combining normal mapping shaders with Box2D shadows.
 *
 * This class provides the core infrastructure for lighting systems that require both visual effects
 * (normal/specular mapping) and physical shadow casting via box2dLight.
 *
 * It handles shader setup, light management, and uniform updates. Subclasses such as [LightEngine]
 * and [Scene2dLightEngine] extend this to provide rendering integration with specific render pipelines
 * (e.g., SpriteBatch or Scene2D).
 *
 * The engine supports directional, point, and spot lights, and includes methods for brightness estimation,
 * light updates, and ambient settings for both shader and Box2D contexts.
 *
 * @param rayHandler The Box2D RayHandler instance used for real-time shadow rendering.
 * @param cam The active OrthographicCamera used for rendering and coordinate transformations.
 * @param batch The SpriteBatch used for drawing with the configured lighting shader.
 * @param viewport The Viewport used to determine screen projection and dimensions.
 * @param useDiffuseLight Enables or disables diffuse lighting mode for the shader.
 * @param maxShaderLights Maximum number of shader lights processed by the engine.
 * @param entityCategory Default category bitmask for newly created Box2D lights.
 * @param entityMask Default mask bitmask for collision filtering of Box2D lights.
 * @param lightActivationRadius The maximum distance from the center within which lights are activated. Use -1 to disable the radius limit.
 */
abstract class AbstractLightEngine(
    val rayHandler: RayHandler,
    val cam: OrthographicCamera,
    val batch: SpriteBatch,
    val viewport: Viewport,
    val useDiffuseLight: Boolean,
    val maxShaderLights: Int = 32,
    val entityCategory: Short = 0x0001.toShort(),
    val entityMask: Short = -1,
    val lightActivationRadius: Float = -1f,
    val lightViewportScale: Float = 2f,
    refreshRateHz: Float? = null,
) {
    protected val vertShader: FileHandle = Gdx.files.internal("shader/light.vert")
    protected val fragShader: FileHandle = Gdx.files.internal("shader/light.frag")
    protected lateinit var shader: ShaderProgram
    protected lateinit var shaderAmbientColor: Color
    protected val activeLights = mutableListOf<GameLight>()
    protected val shaderLights get() = activeLights.take(maxShaderLights)
    protected val managedLights = mutableListOf<GameLight>()
    protected var normalInfluenceValue: Float = 1f
    protected var lastNormalMap: Texture? = null
    protected var lastSpecularMap: Texture? = null
    protected var specularIntensityValue = 32f
    protected var specularRemapMin = 0.1f
    protected var specularRemapMax = 0.5f
    private val density = Gdx.graphics.backBufferScale
    private val lightCam = OrthographicCamera()
    private var lightAcc = 0f
    private var refreshStep: Float? = refreshRateHz?.let { 1f / it }

    init {
        setupShader()
        RayHandler.useDiffuseLight(useDiffuseLight)
        updateShaderAmbientColor(Color(1f, 1f, 1f, 1.0f))
        rayHandler.setAmbientLight(.1f, .1f, .1f, .1f)
    }

    /**
     * Renders all Box2D lights managed by the engine.
     *
     * This method updates the Box2D light system's combined matrix using the current camera,
     * then renders all active Box2D lights (shadows, lightmaps) to the screen.
     * Call this after updating and activating lights, and before drawing the final scene.
     */
    fun renderBox2dLights() {
        lightCam.setToOrtho(false, viewport.worldWidth, viewport.worldHeight)

        lightCam.position.set(cam.position)
        lightCam.zoom = cam.zoom
        lightCam.update()

        rayHandler.setCombinedMatrix(
            lightCam.combined,
            cam.position.x,
            cam.position.y,
            viewport.worldWidth * lightViewportScale,
            viewport.worldHeight * lightViewportScale,
        )

        val step = refreshStep
        if (step == null) {
            // Uncapped: do the full update every frame
            rayHandler.update()
        } else {
            // Capped: fixed update cadence (e.g. 60 Hz)
            lightAcc += Gdx.graphics.deltaTime
            while (lightAcc >= step) {
                rayHandler.update()
                lightAcc -= step
            }
        }
        rayHandler.render()
    }

    /**
     * Sets the batch's shader to the engine's custom lighting shader.
     *
     * This method assigns the engine's lighting shader to the SpriteBatch, enabling
     * advanced lighting effects (normal/specular mapping, dynamic lights) for subsequent draw calls.
     * Call this before rendering objects that should be affected by the lighting system.
     */
    fun setShaderToEngineShader() {
        batch.shader = shader
    }

    /**
     * Sets the batch's shader to the default shader (disables custom lighting shader).
     *
     * This method restores the default rendering behavior by removing the custom lighting shader
     * from the SpriteBatch. After calling this, rendering will use the default pipeline.
     */
    fun setShaderToDefaultShader() {
        batch.shader = null
    }

    /**
     * Sets the batch's shader to a provided custom shader, disabling the engine's lighting shader.
     *
     * This method assigns the given `customShader` to the `SpriteBatch`, overriding the engine's lighting shader.
     * Use this when you want to render with a different shader (e.g., for special effects or post-processing).
     * After calling this, rendering will use the specified custom shader until you restore the engine or default shader.
     * @param customShader The [ShaderProgram] to render.
     */
    fun setShaderToCustomShader(customShader: ShaderProgram) {
        batch.shader = customShader
    }

    /**
     * Sets an overlay color and its strength for the current batch shader.
     *
     * This method flushes the current batch, then updates the shader uniforms
     * `u_overlayColor` and `u_overlayStrength` to apply a color overlay effect.
     *
     * **Warning:** This method calls `batch.flush()` before setting the uniform, which will immediately render all currently batched draw calls.
     * This can affect batching performance and may have side effects if called between draw operations.
     *
     * @param color The overlay color to apply.
     * @param strength The strength of the overlay, clamped between 0.0 and 1.0.
     */
    fun setOverlayColor(
        color: Color,
        strength: Float,
    ) {
        batch.flush()
        batch.shader.setUniformf("u_overlayColor", color)
        batch.shader.setUniformf("u_overlayStrength", strength.coerceIn(0f, 1f))
    }

    /**
     * Resets the overlay color effect in the current batch shader.
     *
     * This method flushes the current batch and sets the overlay strength uniform (`u_overlayStrength`) to 0,
     * effectively disabling any overlay color previously applied.
     *
     * **Warning:** This method calls `batch.flush()`, which immediately renders all currently batched draw calls.
     * Use with care between draw operations to avoid unintended batching side effects.
     */

    fun resetOverlayColor() {
        batch.flush()
        batch.shader.setUniformf("u_overlayStrength", 0f)
    }

    /** Change refresh rate at runtime; pass null to disable capping. */
    fun setRefreshRate(hz: Float?) {
        refreshStep = hz?.takeIf { it > 0f }?.let { 1f / it }
        lightAcc = 0f // reset accumulator for clean phase
    }

    /**
     * Enables or disables **diffuse** compositing for the Box2D lightmap (`RayHandler.useDiffuseLight`).
     *
     * - `true` → *Diffuse / multiplicative* blend: unlit areas are darkened, overall contrast increases.
     *   - Box2D ambient: **RGB** controls perceived brightness; **alpha is ignored** by box2dLight in this mode.
     *   - Normal/specular from the shader are **not** changed mathematically, but appear more pronounced due to the darker base.
     * - `false` → *Additive* blend: the Box2D lightmap is additively added on top of the scene.
     *   - Box2D ambient: **alpha is applied** and can brighten/darken the scene effectively; RGB also contributes.
     *   - Normal/specular may look flatter because the base scene isn’t darkened.
     *
     * @param value `true` to enable diffuse/multiplicative compositing, `false` for additive compositing.
     */
    fun setDiffuseLight(value: Boolean) {
        RayHandler.useDiffuseLight(value)
    }

    /**
     * Adds an existing [GameLight] instance to the light engine.
     *
     * This method is useful if you have created a custom light elsewhere (e.g., from another system or serialized state)
     * and want to register it with the light engine for updates, rendering, and uniform synchronization.
     *
     * Unlike the dedicated creation methods ([addPointLight], [addDirectionalLight], [addSpotLight]), this function does
     * not create a new light but instead integrates a pre-existing one into the engine's rendering and update pipeline.
     *
     * Note: The added light must be compatible with the shader and Box2D lighting setup managed by this engine.
     *
     * @param light The [GameLight] instance to be managed and rendered by the engine.
     */
    fun addLight(light: GameLight) {
        if (managedLights.contains(light)) return

        val newB2dLight =
            when (light) {
                is GameLight.Directional ->
                    DirectionalLight(
                        rayHandler,
                        light.b2dLight.rayNum,
                        light.b2dLight.color,
                        light.b2dLight.direction,
                    )

                is GameLight.Point ->
                    PointLight(
                        rayHandler,
                        light.b2dLight.rayNum,
                        light.b2dLight.color,
                        light.b2dLight.distance,
                        light.b2dLight
                            .position.x,
                        light.b2dLight.position.y,
                    )

                is GameLight.Spot ->
                    ConeLight(
                        rayHandler,
                        light.b2dLight.rayNum,
                        light.b2dLight.color,
                        light.b2dLight.distance,
                        light.b2dLight.position.x,
                        light.b2dLight.position.y,
                        light.b2dLight.direction,
                        (light.b2dLight as ConeLight).coneDegree,
                    )
            }

        light.b2dLight.apply {
            setContactFilter(
                Filter().apply {
                    categoryBits = entityCategory
                    maskBits = entityMask
                },
            )
        }

        light.b2dLight = newB2dLight
        newB2dLight.isActive = false

        managedLights.add(light)
    }

    /**
     * Adds a new directional light to the scene. This light simulates a distant light source,
     * like the sun, where all light rays are parallel.
     *
     * The light is composed of a [ShaderLight] for visual effects on sprites and a
     * [DirectionalLight] for interactions within the Box2D world.
     *
     * @param color The color of the light. The alpha component is multiplied by the intensity.
     * @param direction The direction of the light in degrees, where 0 degrees points to the right (along the positive X-axis).
     * @param initialIntensity The brightness of the light. This value is multiplied with the color's alpha component.
     * @param elevation The elevation of the light source in degrees. An elevation of 0 means the light is parallel to the XY plane.
     * An elevation of 90 degrees would mean the light shines straight down from the Z-axis. This is used to calculate the 3D light vector for the shader.
     * @param rays The number of rays used for the Box2D light. More rays produce higher quality shadows but are more performance-intensive.
     * @param entityCategory Optional: Bitmask defining the category of the light. Defaults to the engine's configured category if not set.
     * @param entityMask Optional: Bitmask defining the collision mask for the light. Defaults to the engine's configured mask if not set.
     * @param isManaged If true, the light is affected by the distance-based culling system. If false, it will always be active if its `isOn` property is true.
     *
     * @return The created [GameLight.Directional] instance, which can be used to modify the light's properties later.
     */
    fun addDirectionalLight(
        color: Color,
        direction: Float,
        initialIntensity: Float,
        elevation: Float = 1f,
        isStatic: Boolean = true,
        isSoftShadow: Boolean = true,
        rays: Int = 128,
        entityCategory: Short = this.entityCategory,
        entityMask: Short = this.entityMask,
        isManaged: Boolean = true,
    ): GameLight.Directional {
        val shaderLight =
            ShaderLight.Directional(
                color = color,
                intensity = initialIntensity,
                direction = direction,
                elevation = elevation,
            )
        val b2dLight =
            DirectionalLight(
                rayHandler,
                rays,
                color,
                direction + 180f,
            ).apply {
                isStaticLight = isStatic
                isSoft = isSoftShadow
            }

        b2dLight.apply {
            isActive = false
            setContactFilter(
                Filter().apply {
                    categoryBits = entityCategory
                    maskBits = entityMask
                },
            )
        }

        val gameLight = GameLight.Directional(shaderLight, b2dLight, isManaged)

        managedLights.add(gameLight)
        return gameLight
    }

    /**
     * Adds a new point light to the scene. This light emanates from a single
     * point in all directions.
     *
     * The light is composed of a [ShaderLight.Point] for visual effects on sprites and a
     * [PointLight] for interactions within the Box2D world.
     *
     * @param position The world position of the light source.
     * @param color The color of the light. The alpha component is multiplied by the intensity.
     * @param initialIntensity The base intensity of the light, affecting both the visual shader and the b2dLight.
     * @param b2dDistance The maximum range of the light. This defines the radius for shadow casting and the falloff calculation.
     * @param falloffProfile A value between 0.0 and 1.0 that controls the shape of the light's falloff. 0.0 is more linear, 1.0 is strongly quadratic.
     * @param shaderIntensityMultiplier A multiplier to fine-tune the visual intensity of the shader light relative to the b2dLight's base intensity.
     * @param rays The number of rays used for the Box2D light. More rays produce higher quality shadows but are more performance-intensive.
     * @param entityCategory Optional: Bitmask defining the category of the light. Defaults to the engine's configured category if not set.
     * @param entityMask Optional: Bitmask defining the collision mask for the light. Defaults to the engine's configured mask if not set.
     * @param isManaged If true, the light is affected by the distance-based culling system. If false, it will always be active if its `isOn` property is true.
     *
     * @return The created [GameLight.Point] instance, which can be used to modify the light's properties later.
     */
    fun addPointLight(
        position: Vector2,
        color: Color,
        initialIntensity: Float = 1f,
        b2dDistance: Float = 1f,
        falloffProfile: Float = 0.5f,
        shaderIntensityMultiplier: Float = 0.5f,
        rays: Int = 128,
        entityCategory: Short = this.entityCategory,
        entityMask: Short = this.entityMask,
        isManaged: Boolean = true,
    ): GameLight.Point {
        val falloff = Falloff.fromDistance(b2dDistance, falloffProfile).toVector3()

        val shaderLight =
            ShaderLight.Point(
                color = color,
                intensity = initialIntensity,
                position = position,
                falloff = falloff,
            )
        val b2dLight =
            PointLight(
                rayHandler,
                rays,
                color,
                b2dDistance,
                position.x,
                position.y,
            )

        b2dLight.apply {
            isActive = false
            setContactFilter(
                Filter().apply {
                    categoryBits = entityCategory
                    maskBits = entityMask
                },
            )
        }

        val gameLight = GameLight.Point(shaderLight, b2dLight, shaderIntensityMultiplier, isManaged)

        managedLights.add(gameLight)
        return gameLight
    }

    /**
     * Adds a new spotlight to the scene, which emits light in a cone shape from a specific point.
     *
     * The light is composed of a [ShaderLight.Spot] for visual effects on sprites and a
     * [ConeLight] for interactions within the Box2D world.
     *
     * @param position The world position of the light source.
     * @param color The color of the light. The alpha component is multiplied by the intensity.
     * @param direction The direction the light is pointing in degrees (e.g., 0 is right, 90 is up).
     * @param coneDegree The **full** angle of the light cone in degrees. A value of 60 creates a 60-degree wide cone.
     * @param initialIntensity The base intensity of the light, affecting both the visual shader and the b2dLight.
     * @param b2dDistance The maximum range of the light. This defines the radius for shadow casting and the falloff calculation.
     * @param falloffProfile A value between 0.0 and 1.0 that controls the shape of the light's falloff. 0.0 is more linear, 1.0 is strongly quadratic.
     * @param shaderIntensityMultiplier A multiplier to fine-tune the visual intensity of the shader light relative to the b2dLight's base intensity.
     * @param rays The number of rays used for the Box2D light. More rays produce higher quality shadows but are more performance-intensive.
     * @param entityCategory Optional: Bitmask defining the category of the light. Defaults to the engine's configured category if not set.
     * @param entityMask Optional: Bitmask defining the collision mask for the light. Defaults to the engine's configured mask if not set.
     * @param isManaged If true, the light is affected by the distance-based culling system. If false, it will always be active if its `isOn` property is true.
     *
     * @return The created [GameLight.Spot] instance, which can be used to modify the light's properties later.
     */
    fun addSpotLight(
        position: Vector2,
        color: Color,
        direction: Float,
        coneDegree: Float,
        initialIntensity: Float = 1f,
        b2dDistance: Float = 1f,
        falloffProfile: Float = 0f,
        shaderIntensityMultiplier: Float = 0.5f,
        rays: Int = 128,
        entityCategory: Short = this.entityCategory,
        entityMask: Short = this.entityMask,
        isManaged: Boolean = true,
    ): GameLight.Spot {
        val falloff = Falloff.fromDistance(b2dDistance, falloffProfile).toVector3()

        val shaderLight =
            ShaderLight.Spot(
                color = color,
                intensity = initialIntensity,
                position = position,
                falloff = falloff,
                directionDegree = direction,
                coneDegree = coneDegree,
            )
        val b2dLight =
            ConeLight(
                rayHandler,
                rays,
                color,
                b2dDistance,
                position.x,
                position.y,
                direction,
                coneDegree / 2,
            )

        b2dLight.apply {
            isActive = false
            setContactFilter(
                Filter().apply {
                    categoryBits = entityCategory
                    maskBits = entityMask
                },
            )
        }

        val gameLight = GameLight.Spot(shaderLight, b2dLight, shaderIntensityMultiplier, isManaged)

        managedLights.add(gameLight)
        return gameLight
    }

    /**
     * Updates the list of active lights based on their distance to a given center point.
     *
     * This method deactivates all currently active lights, sorts the managed lights by their distance
     * to the provided `center` (typically the camera or player position), and then activates the closest
     * lights up to the maximum allowed by the shader (`maxShaderLights`). Only lights within the
     * `lightActivationRadius` (or all if the radius is -1) are activated, except for directional lights,
     * which are always activated regardless of distance.
     * Lights have an `isOn` property to control whether they are enabled,
     * and an `isManaged` property to determine if they are affected by distance-based culling.
     *
     * @param center The world position used as the reference for distance calculations.
     */
    protected fun updateActiveLights(center: Vector2) {
        // Deactivate all previously active lights.
        activeLights.forEach { it.b2dLight.isActive = false }
        activeLights.clear()

        val (culledLights, unmanagedLights) = managedLights.partition { it.isManaged }

        // Activate all unmanaged lights that are currently 'on'.
        for (light in unmanagedLights) {
            if (light.isOn) {
                light.b2dLight.isActive = true
                activeLights.add(light)
            }
        }

        val potentialLights = mutableListOf<GameLight>()
        val radiusSquared = if (lightActivationRadius > 0) lightActivationRadius * lightActivationRadius else -1f

        // Filter lights based on the activation radius.
        for (light in culledLights) {
            // Directional lights are always considered active, regardless of distance.
            if (light is GameLight.Directional) {
                potentialLights.add(light)
                continue
            }

            // Filter lights based on their own 'isOn' state.
            if (!light.isOn) {
                continue
            }

            // For other lights, check if they are within the activation radius.
            if (radiusSquared == -1f || Vector2.dst2(light.b2dLight.x, light.b2dLight.y, center.x, center.y) <= radiusSquared) {
                potentialLights.add(light)
            }
        }

        // Sort the potential lights by distance.
        potentialLights.sortWith { l1, l2 ->
            // Ensure directional lights are not sorted by distance and always appear first.
            val isL1Directional = l1 is GameLight.Directional
            val isL2Directional = l2 is GameLight.Directional
            if (isL1Directional && !isL2Directional) return@sortWith -1
            if (isL2Directional && !isL1Directional) return@sortWith 1
            if (isL1Directional) return@sortWith 0

            // Sort other lights by their squared distance.
            val dst1 = Vector2.dst2(l1.b2dLight.x, l1.b2dLight.y, center.x, center.y)
            val dst2 = Vector2.dst2(l2.b2dLight.x, l2.b2dLight.y, center.x, center.y)
            dst1.compareTo(dst2)
        }

        // Activate the closest lights up to the maximum allowed by the shader.
        for (i in 0 until potentialLights.size.coerceAtMost(maxShaderLights)) {
            val light = potentialLights[i]
            light.b2dLight.isActive = true
            activeLights.add(light)
        }
    }

    /**
     * Sets how strongly the normal map influences the lighting effect.
     * @param normalInfluenceValue A value from 0.0 (no influence, flat lighting) to 1.0 (full influence).
     */
    fun setNormalInfluence(normalInfluenceValue: Float) {
        this.normalInfluenceValue = normalInfluenceValue
    }

    /**
     * Sets the intensity of the specular highlight for the lighting shader.
     *
     * @param intensity The strength of the specular effect. Higher values result in brighter and sharper highlights.
     */
    fun setSpecularIntensity(intensity: Float) {
        specularIntensityValue = intensity
    }

    /**
     * Sets the contrast remapping for the specular map.
     * @param min The gray value in the texture to be treated as black (0.0).
     * @param max The gray value in the texture to be treated as white (1.0).
     */
    fun setSpecularRemap(
        min: Float,
        max: Float,
    ) {
        this.specularRemapMin = min
        this.specularRemapMax = max
    }

    /**
     * Removes a specific light from the engine.
     * @param light The [GameLight] instance to remove.
     */
    fun removeLight(light: GameLight) {
        light.b2dLight.remove()
        activeLights.remove(light)
        managedLights.remove(light)
        shader.bind()
        shader.setUniformi("lightCount", activeLights.size)
    }

    /**
     * Estimates the combined brightness at a given world position based on all point and spotlights.
     *
     * This function sums the contributions of all [GameLight.Point] and [GameLight.Spot] lights at the specified position,
     * using a simple quadratic attenuation model: `attenuation = 1 / (1 + distance^2)`.
     * For spotlights, the contribution is only added if the point is within the cone angle.
     * The result is clamped between 0.0 and 1.0.
     *
     * @param pos The world position to estimate brightness for.
     * @return The estimated brightness as a value between 0.0 (dark) and 1.0 (fully lit).
     */
    fun estimateBrightness(pos: Vector2): Double =
        activeLights
            .sumOf { light ->
                when (light) {
                    is GameLight.Point -> {
                        val dist = light.shaderLight.position.dst(pos)
                        val attenuation = 1f / (1f + dist * dist)
                        (light.shaderLight.intensity * attenuation).toDouble()
                    }

                    is GameLight.Spot -> {
                        val shaderLight = light.shaderLight
                        val dist = shaderLight.position.dst(pos)

                        val directionRad = Math.toRadians(shaderLight.directionDegree.toDouble()).toFloat()
                        val lightDir = Vector2(cos(directionRad), sin(directionRad)).nor()
                        val toPoint = pos.cpy().sub(shaderLight.position).nor()

                        val dot = lightDir.dot(toPoint)
                        val coneHalfAngleRad = Math.toRadians(shaderLight.coneDegree.toDouble() * 0.5)

                        if (dot > cos(coneHalfAngleRad)) {
                            val attenuation = 1f / (1f + dist * dist)
                            (shaderLight.intensity * attenuation).toDouble()
                        } else {
                            0.0
                        }
                    }

                    else -> 0.0
                }
            }.coerceIn(0.0, 1.0)

    /**
     * Removes all dynamic lights from the engine.
     */
    fun clearLights() {
        activeLights.clear()
        managedLights.clear()
        rayHandler.removeAll()
        shader.bind()
        shader.setUniformi("lightCount", 0)
    }

    /**
     * Sets the ambient light for the scene in the shader.
     * This is the base light color and intensity that affects all objects,
     * regardless of dynamic lights.
     * @param ambient The [Color] to use for ambient light. The color's alpha component acts as the intensity.
     */
    fun updateShaderAmbientColor(ambient: Color) {
        shaderAmbientColor = ambient
    }

    /**
     * Sets the ambient light for the scene in box2dLight.
     * This is the base light color and intensity that affects all objects,
     * regardless of dynamic lights.
     * @param ambient The [Color] to use for ambient light. The color's alpha component acts as the intensity (only when diffuseLight is false).
     */
    fun setBox2dAmbientLight(ambient: Color) {
        rayHandler.setAmbientLight(ambient)
    }

    /**
     * Updates the state of all lights. This method should be called once per frame.
     *
     * It iterates through all [GameLight] instances and synchronizes their properties (like color, position, or distance)
     * with the underlying Box2D light objects. This ensures that any changes made to the lights
     * are applied before they are rendered.
     */
    fun update() = activeLights.forEach { it.update() }

    /**
     * Updates and binds all uniform values required by the lighting shader.
     *
     * This method should be called before each render pass to ensure the shader receives
     * up-to-date information about the current lights, ambient settings, screen size, and normal influence.
     *
     * ### What this includes:
     * - Ambient light color and intensity via `ambient`.
     * - Number of active shader lights via `lightCount`.
     * - Normal influence strength via `normalInfluence`.
     * - Viewport offset and size in pixels (used for screen-space calculations).
     * - For each light:
     *   - `lightType` — 0 = directional, 1 = point, 2 = spot.
     *   - `lightColor` — light color * intensity.
     *   - `lightDir` — light direction vector (for directional and spotlights).
     *   - `lightPos` — light position in screen space (for point and spotlights).
     *   - `falloff` — attenuation values (for point and spotlights).
     *   - `coneAngle` — cosine of half cone angle (for spotlights only).
     *
     * Notes:
     * - Only the first [maxShaderLights] lights are considered for shader lighting.
     * - This method assumes the shader is already bound and `batch.shader` is not null.
     */
    protected fun applyShaderUniforms() {
        val shader = batch.shader ?: return
        shader.bind()
        shader.setUniformi("lightCount", shaderLights.size)
        shader.setUniformf("normalInfluence", normalInfluenceValue)
        shader.setUniformf("ambient", shaderAmbientColor)
        shader.setUniformf("u_specularIntensity", specularIntensityValue)
        shader.setUniformf("u_specularRemapMin", specularRemapMin)
        shader.setUniformf("u_specularRemapMax", specularRemapMax)

        // reset the color overlay
        shader.setUniformf("u_overlayStrength", 0f)

        // Scale the viewport uniforms to match the physical pixel space of gl_FragCoord.
        val screenX = viewport.screenX * density
        val screenY = viewport.screenY * density
        val screenW = viewport.screenWidth * density
        val screenH = viewport.screenHeight * density

        shader.setUniformf("u_viewportOffset", screenX, screenY)
        shader.setUniformf("u_viewportSize", screenW, screenH)

        for (i in shaderLights.indices) {
            val gameLight = activeLights[i]
            val data = activeLights[i].shaderLight
            val prefix = "[$i]"
            shader.setUniformf("lightColor$prefix", vec4(data.color.r, data.color.g, data.color.b, data.color.a * data.intensity))
            shader.setUniformf("shininess", 32f)

            when (data) {
                is ShaderLight.Directional -> {
                    shader.setUniformi("lightType$prefix", 0)

                    val dirRad = Math.toRadians(data.direction.toDouble()).toFloat()
                    val eleRad = Math.toRadians(data.elevation.toDouble()).toFloat()

                    val directionVector =
                        vec3(
                            cos(dirRad) * cos(eleRad),
                            sin(dirRad) * cos(eleRad),
                            sin(eleRad),
                        ).nor()

                    shader.setUniformf("lightDir$prefix", directionVector)
                }

                is ShaderLight.Point -> {
                    shader.setUniformi("lightType$prefix", 1)

                    val pointLight = gameLight as GameLight.Point
                    val shaderIntensity = data.intensity * pointLight.shaderIntensityMultiplier
                    shader.setUniformf("lightColor$prefix", vec4(data.color.r, data.color.g, data.color.b, data.color.a * shaderIntensity))

                    val screenPos = worldToScreenSpace(vec3(data.position.x, data.position.y, 0f), cam, viewport)
                    shader.setUniformf("lightPos[$i]", screenPos)
                    shader.setUniformf("falloff$prefix", data.falloff)
                }

                is ShaderLight.Spot -> {
                    shader.setUniformi("lightType$prefix", 2)

                    val pointLight = gameLight as GameLight.Spot
                    val shaderIntensity = data.intensity * pointLight.shaderIntensityMultiplier
                    shader.setUniformf("lightColor$prefix", vec4(data.color.r, data.color.g, data.color.b, data.color.a * shaderIntensity))

                    val screenPos = worldToScreenSpace(vec3(data.position.x, data.position.y, 0f), cam, viewport)
                    shader.setUniformf("lightPos[$i]", screenPos)
                    shader.setUniformf("falloff$prefix", data.falloff)

                    val rad = Math.toRadians(data.directionDegree.toDouble()).toFloat()
                    val directionVector = vec3(cos(rad), sin(rad), 0f)

                    shader.setUniformf("lightDir$prefix", directionVector)
                    shader.setUniformf("coneAngle$prefix", cos(Math.toRadians(data.coneDegree.toDouble() * 0.5)).toFloat())
                }
            }
        }
    }

    open fun resize(
        width: Int,
        height: Int,
    ) {
        viewport.update(width, height, true)
        val scale = Gdx.graphics.backBufferScale
        rayHandler.setCombinedMatrix(cam)
        shader.bind()
        shader.setUniformf("resolution", width.toFloat() * scale, height.toFloat() * scale)
    }

    fun dispose() {
        clearLights()
        rayHandler.disposeSafely()
        shader.disposeSafely()
    }

    private fun setupShader() {
        ShaderProgram.pedantic = false
        shader = ShaderProgram(vertShader, fragShader)

        if (!shader.isCompiled) {
            throw GdxRuntimeException("Could not compile shader: ${shader.log}")
        }

        shader.bind()
        shader.setUniformi("u_normals", 1)
        shader.setUniformi("u_specular", 2)
    }
}
