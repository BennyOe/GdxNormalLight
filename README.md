# GDX Normal-Mapped Lighting Engine

A powerful 2D lighting engine for **LibGDX**, combining dynamic per-pixel lighting, **normal mapping**, and **Box2D shadows**. Supports both `scene2d` and ECS-based architectures like Fleks, with a focus on flexibility and real-time performance.

### 🔸 Point Light Effect

![Point Light Demo](https://github.com/user-attachments/assets/d5b5b969-47a9-4c21-84cd-7a42be94af94)


### 🔸 Spot Light Effect

![Spot Light Demo](https://github.com/user-attachments/assets/db9a0057-a742-4b1e-8826-a9ad9786d7e1)

## ✨ Features

- **Dynamic Per-Pixel Lighting**  
  Smooth, realistic lighting effects calculated per pixel.

- **Normal Mapping**  
  Adds depth and surface detail to 2D sprites using normal maps.

- **Specular Mapping**  
  Enables shiny, reflective surfaces using specular maps.

- **Multiple Light Types**  
  - **Point Lights**: Omnidirectional, with customizable falloff.  
  - **Spot Lights**: Cone-shaped, for flashlights or beams.  
  - **Directional Lights**: Global light from a direction, like sunlight.

- **Box2D Integration**  
  Uses `box2dlights` for real-time, physics-based shadow casting.

- **Scene2d Integration**  
  Seamless lighting for `scene2d` actors and stages.

- **Light culling**  
  Automatically deactivates distant lights to optimize performance.

- **Variable Refresh Rate**
  Optional Box2D light update capping via refreshRateHz (fixed cadence) to stabilize performance spikes; can be changed at runtime using setRefreshRate(hz)

- **Multipass Rendering**
  Multi-pass friendly pipeline: render Box2D lights via renderBox2dLights() independently from sprite drawing/compositing

- **Effect System**  
  Lights can have built-in effects like:
  - `FIRE`
  - `PULSE`
  - `FAULTY_LAMP`
  - `LIGHTNING`
  - `COLOR_CYCLE`
  - `NONE`

- **Flexible API Design**  
  - `LightEngine`: Low-level, ECS-compatible engine.  
  - `Scene2dLightEngine`: High-level, plug-and-play engine for scene2d users.
 
---

## ⚙️ Engine Setup & Usage

### Gradle Setup

#### For Kotlin (libKtx) Projects
```kotlin
// In core/build.gradle.kts
dependencies {
    implementation("io.github.bennyoe:gdx-normal-light:1.0.5")
}
```

#### For Java Projects
```kotlin
// The following dependencies are required only for pure Java LibGDX projects (not Kotlin):
dependencies {
    implementation("io.github.bennyoe:gdx-normal-light:1.0.1")
    api("io.github.libktx:ktx-math:$ktxVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
}
```

### Low-Level API (`LightEngine`)

#### Initialization

```kotlin
val rayHandler = RayHandler(world)
val lightEngine = LightEngine(rayHandler, cam, batch, viewport)
```

#### Adding Lights

```kotlin
val pointLight = lightEngine.addPointLight(
    position = Vector2(5f, 5f),
    color = Color(1f, 0.5f, 0.2f, 1f),
    initialIntensity = 10f, // Base intensity for the light
    b2dDistance = 15f,      // Range for Box2D shadow casting
    shaderIntensityMultiplier = 0.8f // Fine-tune the visual brightness in the shader
)
pointLight.effect = LightEffectType.FIRE
```

#### Rendering Loop

```kotlin
lightEngine.update()

lightEngine.renderLights { engine ->
    engine.draw(diffuseTexture, normalMapTexture, specularTexture, x, y, width, height)
    engine.draw(diffuseTexture, normalMapTexture, x, y, width, height)
    engine.draw(diffuseTexture, x, y, width, height) // Unlit
}
```

---

### High-Level API (`Scene2dLightEngine`)

#### Initialization

```kotlin
val stage = Stage(viewport, batch)
val lightEngine = Scene2dLightEngine(rayHandler, cam, batch, viewport, stage)
```

#### NormalMappedActor

```kotlin
val myActor = NormalMappedActor(diffuseTexture, normalMapTexture)
stage.addActor(myActor)
```

#### LightActor

```kotlin
val pointLight = lightEngine.addPointLight(...)
val lightActor = LightActor(pointLight)
stage.addActor(lightActor)
```

#### Rendering Loop

```kotlin
stage.act(delta)
lightEngine.update()

lightEngine.renderLights { engine ->
    for (actor in stage.actors) {
        engine.draw(actor)
    }
}
```

### 🔦 Light Culling & Performance Optimization

The engine automatically performs **light culling** every frame based on proximity to a specified center (usually the player or camera). Lights that are too far away from this center are temporarily deactivated unless they are directional lights, which are always active.

This mechanism:
- Improves performance by limiting shader computations to only nearby lights and turning off box2d-lights that are not in range.
- Ensures the engine respects the configured `maxShaderLights` limit (default: 32).
- Is configurable via `lightActivationRadius`, which defines the maximum distance for lights to be considered "active". Set to `-1f` to disable distance-based culling.

Directional lights are always included in the lighting pass, regardless of distance.

---

## 🧪 Demo Projects

The repo includes two demos:

- `LightDemo`: Uses `LightEngine` with ECS-style rendering
- `Scene2dLightDemo`: Uses `Scene2dLightEngine` with actors

To run a demo, open `GgdxNormalMapExample.kt` and modify the demo class:

```kotlin
addScreen(LightDemo())
setScreen<LightDemo>()
// or
addScreen(Scene2dLightDemo())
setScreen<Scene2dLightDemo>()
```

Then execute:

```bash
./gradlew run
```

---

## ⌨️ Key Bindings (Demo)

| Key           | Action                               |
|---------------|--------------------------------------|
| `1`, `2`      | Switch between Point and Spot lights |
| `BACKSPACE`   | Toggle directional light             |
| `SPACE`       | Toggle diffuse lighting              |
| `N`           | Toggle normal map lighting           |
| `Q` / `A`     | Increase / decrease shader intensity |
| `W` / `S`     | Increase / decrease light distance   |
| `E` / `D`     | Adjust shader intensity multiplier   |
| `R` / `F`     | Adjust spot cone angle               |
| `T` / `G`     | Rotate spotlight cone                |
| `I` / `K`     | Adjust directional light intensity   |
| `O` / `L`     | Rotate directional light             |
| `Y` / `H`     | Adjust specular intensity            |
| Mouse Wheel   | Change light hue                     |
| Mouse Move    | Move active light                    |

---

## 📦 Dependencies

- **Language**: Kotlin
- **Framework**: LibGDX
- **Physics**: Box2D
- **Lighting**: box2dlights
- **Optional**: LibKTX (for demos)

---

## 🧠 Core Concepts

| Engine Type           | Description                                                         |
|-----------------------|---------------------------------------------------------------------|
| `LightEngine`         | Low-level core renderer, no assumptions about actors/entities       |
| `Scene2dLightEngine`  | Wraps `LightEngine`, works with `scene2d` stages and actors         |

---

## 🙏 Credits

- Based on [mattdesl's shader gist](https://gist.github.com/mattdesl/4653464)
- Thanks to [Quillraven](https://github.com/Quillraven) for inspiration [YouTube Tutorials](https://www.youtube.com/@Quillraven)
