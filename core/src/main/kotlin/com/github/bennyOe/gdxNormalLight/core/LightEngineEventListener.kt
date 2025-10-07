package com.github.bennyOe.gdxNormalLight.core

import com.badlogic.gdx.math.Vector2
import kotlin.collections.forEach
import kotlin.jvm.javaClass

/**
 * Manages consumers and broadcasts lightEngine events.
 */
object LightEngineEventListener {
    private val lightEngineConsumerList: MutableList<LightEngineEventConsumer> = mutableListOf()

    /**
     * Adds a consumer to the listener.
     *
     * @param lightEngineEventConsumer the consumer to add
     */
    fun subscribe(lightEngineEventConsumer: LightEngineEventConsumer) {
        lightEngineConsumerList.add(lightEngineEventConsumer)
        println("Consumer: ${lightEngineEventConsumer.javaClass.simpleName} successfully added to LightEngine EventListener")
    }

    /**
     * Removes a consumer from the listener.
     *
     * @param lightEngineEventConsumer the consumer to remove
     */
    fun unsubscribe(lightEngineEventConsumer: LightEngineEventConsumer) {
        lightEngineConsumerList.remove(lightEngineEventConsumer)
        println("Consumer: ${lightEngineEventConsumer.javaClass.simpleName} successfully removed from LightEngine EventListener")
    }

    /**
     * Triggers the [onEvent] callback on all registered consumers.
     */
    fun emit(event: LightEngineEvent) {
        lightEngineConsumerList.forEach { consumer -> consumer.onEvent(event) }
    }
}

/**
 * An observer that reacts to lightEngine events.
 */
interface LightEngineEventConsumer {
    /**
     * Called when a lightning event is emitted.
     */
    fun onEvent(event: LightEngineEvent)
}

interface LightEngineEvent

class LightningEvent : LightEngineEvent

class FaultyLightEvent(
    val lightIsOn: Boolean,
    val position: Vector2,
) : LightEngineEvent
