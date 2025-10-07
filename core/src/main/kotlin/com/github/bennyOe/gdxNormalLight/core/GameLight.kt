package com.github.bennyOe.gdxNormalLight.core

import box2dLight.ConeLight
import box2dLight.Light
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.math.Vector2

interface IGameLight {
    fun update()
}

sealed class GameLight(
    open val shaderLight: ShaderLight,
    open var b2dLight: Light,
    open var isManaged: Boolean = true,
    internal val baseIntensity: Float = shaderLight.intensity,
    internal val baseColor: Color = shaderLight.color,
    internal val baseDistance: Float = b2dLight.distance,
) : IGameLight {
    val effectParams: LightEffectParameters = LightEffectParameters()
    var effect: LightEffectType? = null
    var didLightningEventFire: Boolean = false
    var didFaultyLampEventFire: Boolean = false
    var enableLightning: Boolean = false

    var isOn: Boolean = true
        private set
    private var lastIntensity: Float = baseIntensity

    internal var flickerTimer = 0f
    internal var elapsedTime = 0f
    internal val currentTargetColor = baseColor
    internal var currentTargetIntensity = baseIntensity

    fun setOn(active: Boolean) {
        if (isOn == active) return

        this.isOn = active
        b2dLight.isActive = active

        if (active) {
            shaderLight.intensity = lastIntensity
        } else {
            lastIntensity = shaderLight.intensity
            shaderLight.intensity = 0f
        }
    }

    fun toggle() = setOn(!isOn)

    abstract override fun update()

    var color: Color
        get() = b2dLight.color
        set(value) {
            b2dLight.color = value
        }

    data class Directional(
        override val shaderLight: ShaderLight.Directional,
        override var b2dLight: Light,
        override var isManaged: Boolean = true,
    ) : GameLight(shaderLight, b2dLight) {
        var intensity: Float
            get() = shaderLight.intensity
            set(value) {
                shaderLight.intensity = value
            }

        var direction: Float
            get() = shaderLight.direction
            set(value) {
                shaderLight.direction = value
                b2dLight.direction = value
            }

        override fun update() {
            applyLightEffect(this)
            shaderLight.color.set(b2dLight.color)
            shaderLight.direction = b2dLight.direction
        }
    }

    data class Point(
        override val shaderLight: ShaderLight.Point,
        override var b2dLight: Light,
        var shaderIntensityMultiplier: Float = 1.0f,
        override var isManaged: Boolean = true,
    ) : GameLight(shaderLight, b2dLight) {
        var position: Vector2
            get() = b2dLight.position
            set(value) {
                b2dLight.position = value
            }

        // --- Independent Properties ---
        var shaderIntensity: Float
            get() = shaderLight.intensity
            set(value) {
                shaderLight.intensity = value
            }

        var distance: Float
            get() = b2dLight.distance
            set(value) {
                b2dLight.distance = value
                shaderLight.falloff = Falloff.fromDistance(value).toVector3()
            }

        override fun update() {
            applyLightEffect(this)
            b2dLight.setPosition(b2dLight.position.x, b2dLight.position.y)

            shaderLight.position = b2dLight.position
            shaderLight.color.set(b2dLight.color)
            shaderLight.falloff = Falloff.fromDistance(b2dLight.distance).toVector3()
        }
    }

    data class Spot(
        override val shaderLight: ShaderLight.Spot,
        override var b2dLight: Light,
        var shaderIntensityMultiplier: Float = 1.0f,
        override var isManaged: Boolean = true,
    ) : GameLight(shaderLight, b2dLight) {
        var position: Vector2
            get() = b2dLight.position
            set(value) {
                b2dLight.position = value
            }

        var direction: Float
            get() = shaderLight.directionDegree
            set(value) {
                b2dLight.direction = value
            }

        // --- Independent Properties ---
        var shaderIntensity: Float
            get() = shaderLight.intensity
            set(value) {
                shaderLight.intensity = value
            }

        var distance: Float
            get() = b2dLight.distance
            set(value) {
                b2dLight.distance = value
            }

        var coneDegree: Float
            get() = (b2dLight as ConeLight).coneDegree * 2f
            set(value) {
                shaderLight.coneDegree = value
                (b2dLight as ConeLight).coneDegree = value / 2f
            }

        override fun update() {
            applyLightEffect(this)

            shaderLight.position = b2dLight.position
            shaderLight.color.set(b2dLight.color)
            shaderLight.directionDegree = b2dLight.direction
            shaderLight.coneDegree = (b2dLight as ConeLight).coneDegree * 2f
            shaderLight.falloff = Falloff.fromDistance(b2dLight.distance).toVector3()
        }
    }
}
