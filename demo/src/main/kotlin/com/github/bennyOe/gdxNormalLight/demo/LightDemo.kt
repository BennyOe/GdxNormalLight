package com.github.bennyOe.gdxNormalLight.demo

import box2dLight.RayHandler
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector3
import com.github.bennyOe.gdxNormalLight.core.GameLight
import com.github.bennyOe.gdxNormalLight.core.LightEngine
import ktx.assets.disposeSafely
import ktx.math.vec2

/**
 * Demo screen for showcasing dynamic 2D lighting using Box2D and LibGDX.
 *
 * This class demonstrates the use of different light types (directional, point, and spot)
 * and provides interactive controls for manipulating their properties in real time.
 *
 * Features:
 * - Switch between point and spotlights.
 * - Toggle the directional light on and off.
 * - Adjust intensity, distance, balance, and cone angle of lights.
 * - Change light color using the mouse wheel.
 * - Move the active light with the mouse cursor.
 * - Add light effects [com.github.bennyOe.gdxNormalLight.core.LightEffectType]
 *
 * Controls:
 * - 1/2: Switch active light (point/spot)
 * - BACKSPACE: Toggle directional light
 * - SPACE: Toggle diffuse lighting
 * - N: Toggle normal maps on/off
 * - Q/A: Increase/decrease active light shader intensity
 * - W/S: Increase/decrease active light distance
 * - E/D: Increase/decrease active light shader balance
 * - R/F: Increase/decrease spot light cone degree (only for spot light)
 * - T/G: Increase/decrease spot light cone rotation (only for spot light)
 * - Y/H: Increase/decrease specular intensity
 * - I/K: Increase/decrease directional light intensity
 * - O/L: Rotate directional light
 * - Mouse wheel: Change active light color
 *
 * @author Benjamin Oechsle
 */
class LightDemo : AbstractLightDemo() {
    // --- Constants for tweaking controls ---
    companion object {
        private const val LIGHT_INTENSITY_SPEED = 2.5f
        private const val LIGHT_DISTANCE_SPEED = 4.5f
        private const val LIGHT_BALANCE_SPEED = 2.5f
        private const val SPOT_CONE_ANGLE_SPEED = 15f
        private const val SPOT_CONE_ROTATION_SPEED = 55f
        private const val DIRECTIONAL_INTENSITY_SPEED = 2.5f
        private const val DIRECTIONAL_ANGLE_SPEED = 15f
        private const val COLOR_SCROLL_SPEED = 5f
        private const val SPECULAR_INTENSITY_SPEED = 40f
    }

    private lateinit var lightEngine: LightEngine

    // --- Light Declarations ---
    private lateinit var directionalLight: GameLight.Directional
    private lateinit var pointLight: GameLight.Point
    private lateinit var spotLight: GameLight.Spot

    // --- State Management ---
    private var activeLight: GameLight? = null
    private var isDirectionalLightOn = true
    private var yScrollAmount = 0f
    private var isNormalInfluence: Boolean = true
    private var specularIntensity: Float = 32f

    override fun show() {
        super.show()
        lightEngine = LightEngine(rayHandler, cam, batch, viewport)

        // Set up a simple input processor to listen for mouse scroll events.
        Gdx.input.inputProcessor =
            object : InputAdapter() {
                override fun scrolled(
                    amountX: Float,
                    amountY: Float,
                ): Boolean {
                    yScrollAmount = amountY
                    return true
                }
            }

        // --- Light Creation ---
        directionalLight =
            lightEngine.addDirectionalLight(
                color = Color(0.8f, 0.8f, 1f, 0.45f),
                direction = -45f,
                initialIntensity = 2.8f,
                elevation = 40f,
            )

        pointLight =
            lightEngine.addPointLight(
                position = vec2(6f, 6f),
                color = Color(1f, 0.5f, 0.2f, 1f),
                initialIntensity = 2f,
                b2dDistance = 7f,
                falloffProfile = 1f,
                shaderIntensityMultiplier = 1f,
            )

        spotLight =
            lightEngine.addSpotLight(
                position = vec2(6f, 5f),
                color = Color(0.2f, 0.5f, 1f, 1f),
                direction = 0f,
                coneDegree = 75f,
                initialIntensity = 4f,
                b2dDistance = 10f,
                falloffProfile = 0.5f,
                shaderIntensityMultiplier = 2f,
            )

        // --- Initial Scene Setup ---
        lightEngine.removeLight(spotLight) // Start with the spotlight disabled
        activeLight = pointLight // Point light is active by default

        lightEngine.setNormalInfluence(1f)
        lightEngine.setSpecularRemap(0.3f, 0.7f)
        lightEngine.setSpecularIntensity(specularIntensity)
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        lightEngine.resize(width, height)
    }

    override fun render(delta: Float) {
        // Handle all user input
        handleInput(delta)

        // Update physics and camera
        world.step(1 / 60f, 6, 2)
        cam.update()
        viewport.apply()

        // Update and render the light engine
        lightEngine.update()

        lightEngine.renderLights { engine ->
            // optional textures to toggle

            engine.draw(wall, wallNormals, 0f, 0f, 9f, 19f)
            engine.draw(wood, woodNormals, 9f, 0f, 9f, 19f)
//            engine.draw(plank, plankNormals, plankSpecular, 0f, 0f, 19f, 19f)
        }
        // Render Box2D debug lines
        debugRenderer.render(world, cam.combined)
    }

    /**
     * Main input handling function, called every frame.
     */
    private fun handleInput(delta: Float) {
        updateActiveLightPosition()
        handleLightSwitching()
        handleGlobalControls(delta)
        handleActiveLightControls(delta)
        handleColorChange()
    }

    /**
     * Makes the currently active light follow the mouse cursor.
     */
    private fun updateActiveLightPosition() {
        val mousePos = viewport.unproject(Vector3(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0f))
        when (val light = activeLight) {
            is GameLight.Point -> light.position = vec2(mousePos.x, mousePos.y)
            is GameLight.Spot -> light.position = vec2(mousePos.x, mousePos.y)
            else -> {}
        }
    }

    /**
     * Handles switching between the point light and spotlight.
     */
    private fun handleLightSwitching() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1) && activeLight != pointLight) {
            switchActiveLight(pointLight)
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_2) && activeLight != spotLight) {
            switchActiveLight(spotLight)
        }
    }

    /**
     * Handles controls that are always active (directional light, diffuse toggle).
     */
    private fun handleGlobalControls(delta: Float) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.BACKSPACE)) {
            isDirectionalLightOn = !isDirectionalLightOn
            if (isDirectionalLightOn) lightEngine.addLight(directionalLight) else lightEngine.removeLight(directionalLight)
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            RayHandler.useDiffuseLight(!RayHandler.isDiffuse)
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.N)) {
            if (isNormalInfluence) lightEngine.setNormalInfluence(0f) else lightEngine.setNormalInfluence(1f)
            isNormalInfluence = !isNormalInfluence
        }

        // specular controls
        if (Gdx.input.isKeyPressed(Input.Keys.Y)) {
            specularIntensity += SPECULAR_INTENSITY_SPEED * delta
            lightEngine.setSpecularIntensity(specularIntensity)
        }
        if (Gdx.input.isKeyPressed(Input.Keys.H)) {
            specularIntensity -= SPECULAR_INTENSITY_SPEED * delta
            lightEngine.setSpecularIntensity(specularIntensity)
        }

        // Always allow control over the directional light
        val dirIntensitySpeed = DIRECTIONAL_INTENSITY_SPEED * delta
        val dirAngleSpeed = DIRECTIONAL_ANGLE_SPEED * delta
        if (Gdx.input.isKeyPressed(Input.Keys.I)) directionalLight.shaderIntensity += dirIntensitySpeed
        if (Gdx.input.isKeyPressed(Input.Keys.K)) directionalLight.shaderIntensity -= dirIntensitySpeed
        if (Gdx.input.isKeyPressed(Input.Keys.O)) directionalLight.direction += dirAngleSpeed
        if (Gdx.input.isKeyPressed(Input.Keys.L)) directionalLight.direction -= dirAngleSpeed
    }

    /**
     * Handles controls for the currently active light (Point or Spot).
     */
    private fun handleActiveLightControls(delta: Float) {
        val light = activeLight ?: return // Exit if no light is active

        val intensitySpeed = LIGHT_INTENSITY_SPEED * delta
        val distanceSpeed = LIGHT_DISTANCE_SPEED * delta
        val balanceSpeed = LIGHT_BALANCE_SPEED * delta

        when (light) {
            is GameLight.Point -> {
                if (Gdx.input.isKeyPressed(Input.Keys.Q)) light.shaderIntensity += intensitySpeed
                if (Gdx.input.isKeyPressed(Input.Keys.A)) light.shaderIntensity -= intensitySpeed
                if (Gdx.input.isKeyPressed(Input.Keys.W)) light.distance += distanceSpeed
                if (Gdx.input.isKeyPressed(Input.Keys.S)) light.distance -= distanceSpeed
                if (Gdx.input.isKeyPressed(Input.Keys.E)) light.shaderIntensityMultiplier += balanceSpeed
                if (Gdx.input.isKeyPressed(Input.Keys.D)) light.shaderIntensityMultiplier -= balanceSpeed
            }

            is GameLight.Spot -> {
                if (Gdx.input.isKeyPressed(Input.Keys.Q)) light.shaderIntensity += intensitySpeed
                if (Gdx.input.isKeyPressed(Input.Keys.A)) light.shaderIntensity -= intensitySpeed
                if (Gdx.input.isKeyPressed(Input.Keys.W)) light.distance += distanceSpeed
                if (Gdx.input.isKeyPressed(Input.Keys.S)) light.distance -= distanceSpeed
                if (Gdx.input.isKeyPressed(Input.Keys.E)) light.shaderIntensityMultiplier += balanceSpeed
                if (Gdx.input.isKeyPressed(Input.Keys.D)) light.shaderIntensityMultiplier -= balanceSpeed

                // --- Spot light specific controls ---
                val coneSpeed = SPOT_CONE_ANGLE_SPEED * delta
                if (Gdx.input.isKeyPressed(Input.Keys.R)) light.coneDegree += coneSpeed
                if (Gdx.input.isKeyPressed(Input.Keys.F)) light.coneDegree -= coneSpeed

                val coneRotationSpeed = SPOT_CONE_ROTATION_SPEED * delta
                if (Gdx.input.isKeyPressed(Input.Keys.T)) light.direction += coneRotationSpeed
                if (Gdx.input.isKeyPressed(Input.Keys.G)) light.direction -= coneRotationSpeed
            }

            else -> Unit
        }
    }

    /**
     * Changes the color of the active light when the mouse wheel is scrolled.
     */
    private fun handleColorChange() {
        if (yScrollAmount != 0f) {
            activeLight?.let { light ->
                val color = light.color
                val hsv = color.toHsv(FloatArray(3))
                // Change the HUE value to cycle through colors
                hsv[0] = (hsv[0] - yScrollAmount * COLOR_SCROLL_SPEED + 360) % 360
                light.color = color.fromHsv(hsv)
            }
            // Reset scroll amount after processing
            yScrollAmount = 0f
        }
    }

    /**
     * Utility function to swap the active light in the engine.
     */
    private fun switchActiveLight(newLight: GameLight) {
        activeLight?.let { lightEngine.removeLight(it) }
        activeLight = newLight
        lightEngine.addLight(activeLight!!)
    }

    override fun dispose() {
        batch.disposeSafely()
        wall.disposeSafely()
        wallNormals.disposeSafely()
        wood.disposeSafely()
        wallNormals.disposeSafely()
        lightEngine.dispose()
    }
}
