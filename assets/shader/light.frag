#ifdef GL_ES
    // Define LOWP for GL ES (mobile/web) for lower precision, which can improve performance.
#define LOWP lowp
    // Set default precision for floats to medium. Required in GL ES fragment shaders.
precision mediump float;
#else
    // On desktop OpenGL, LOWP is not a keyword, so define it as empty.
#define LOWP
#endif

#define MAX_LIGHTS 32          // The maximum number of lights the shader can process.

// Varyings are passed from the vertex to the fragment shader and are interpolated per-fragment.
varying LOWP vec4 v_color;   // Vertex color, usually white unless tinted.
varying vec2 v_texCoord;     // Texture coordinates (UVs) for the current fragment.

// Whether to flip the texture horizontally (mirrors the image on the X axis).
uniform bool u_flipX;

// Uniforms are variables that are constant for all fragments in a single draw call.
uniform sampler2D u_texture;       // Diffuse texture (unit 0)
uniform sampler2D u_normals;       // Normal map texture (unit 1)
uniform sampler2D u_specular;      // Specular map texture (unit 2)

uniform bool u_useNormalMap;   // Whether to use the normal map or not.
uniform bool u_useSpecularMap; // Whether to use the specular map or not.

uniform vec2 resolution;      // The resolution of the application window.
uniform float normalInfluence;// How much the normal map affects lighting (0.0 - 1.0).
uniform float shininess;      // Shiny factor for specular highlights
uniform float u_specularIntensity;
uniform vec4 ambient;         // Ambient light color (rgb) and intensity (a).

// Specular remap values to adjust the specular map's range.
uniform float u_specularRemapMin;
uniform float u_specularRemapMax;

uniform int lightCount;       // The actual number of active lights.

// Viewport uniforms to handle screen areas correctly (e.g., with letterboxing).
uniform vec2 u_viewportOffset;// The (x, y) offset of the viewport in screen pixels.
uniform vec2 u_viewportSize;  // The (width, height) of the viewport in screen pixels.

// Per-light properties, passed as arrays from the Kotlin code.
uniform int lightType[MAX_LIGHTS];    // 0:DIRECTIONAL, 1:POINT, 2:SPOT
uniform vec3 lightPos[MAX_LIGHTS];    // Light's position in normalized viewport space (0.0 - 1.0).
uniform vec3 lightDir[MAX_LIGHTS];    // Light's direction vector.
uniform vec4 lightColor[MAX_LIGHTS];  // Light's color (rgb) and intensity (a).
uniform float coneAngle[MAX_LIGHTS];  // For spotlights, the pre-calculated cosine of the cone's half-angle.
uniform vec3 falloff[MAX_LIGHTS];     // For point/spot lights, the constant, linear, and quadratic falloff factors.

// color overlay for effects like underwater tinting, power-up glows, hitstop, etc.
uniform vec4 u_overlayColor;
uniform float u_overlayStrength;

void main() {
    // 1. Get the base color from the diffuse texture.
    vec4 diffuseColor = texture2D(u_texture, v_texCoord);
    vec3 specColor = texture2D(u_specular, v_texCoord).rgb;

    // Decide whether to use a normal map if it is available.
    vec3 n;
    if (u_useNormalMap) {
        // 2. Get the normal vector from the normal map.
        // Texture values are in the 0-1 range, so map them to the -1 to 1 range.
        vec3 normalMap = texture2D(u_normals, v_texCoord).rgb;
        n = normalize(normalMap * 2.0 - 1.0);

        // Flip the normal's x-component if the sprite is flipped.
        if (u_flipX) {
            n.x = -n.x;
        }
    } else {
        // Fallback to a default normal if no normal map is used.
        n = vec3(0.0, 0.0, 1.0); // Default normal pointing out of the screen.
    }

    // 3. Initialize the final light color with the ambient light contribution.
    vec3 totalLight = ambient.rgb * ambient.a;
    vec3 totalSpecular = vec3(0.0);

    // 4. Calculate the current fragment's position in normalized viewport space (0.0 to 1.0).
    vec2 fragCoord_viewport = gl_FragCoord.xy - u_viewportOffset;
    vec3 fragPos = vec3(fragCoord_viewport / u_viewportSize, 0.0);

    // View direction (camera is at z=1, looking towards z=0)
    vec3 viewDir = normalize(vec3(0.0, 0.0, 1.0));

    // 5. Loop through all active lights and add their contributions.
    for (int i = 0; i < lightCount; i++) {
        vec3 l;                  // The final, normalized vector from the fragment to the light.
        float attenuation = 1.0; // Default attenuation (no falloff for directional lights).
        float spotFactor = 1.0;  // Default spotlight factor (no cone effect).

        if (lightType[i] == 0) { // Light type 0 is DIRECTIONAL
                                 // For directional lights, the light vector is simply its constant direction.
                                 l = -lightDir[i];

        } else { // For POINT and SPOT lights
                 // 5a. Calculate the vector from the fragment to the light source.
                 vec3 fragToLight = lightPos[i] - fragPos;

                 // 5b. Apply aspect ratio correction to prevent stretching.
                 // This ensures the light's shape is correct regardless of window dimensions.
                 fragToLight.x *= u_viewportSize.x / u_viewportSize.y;

                 // 5c. Calculate the final, normalized light vector 'l' and the distance 'd'.
                 float d = length(fragToLight);
                 l = normalize(fragToLight);

                 // 5d. Calculate attenuation (light falloff) based on distance.
                 attenuation = 1.0 / (falloff[i].x + (falloff[i].y * d) + (falloff[i].z * d * d));

                 // 5e. If it's a spotlight, calculate the cone effect.
                 if (lightType[i] == 2) { // Light type 2 is SPOT
                                          // Create a modifiable copy of the light's direction vector.
                                          vec3 corrected_lightDir = lightDir[i];

                                          // Calculate the angle (theta) by comparing the two vectors in the SAME corrected space.
                                          float theta = dot(normalize(corrected_lightDir), -l);

                                          // Calculate a soft edge for the cone that is relative to the cone's size.
                                          // This ensures the light's core brightness is constant, regardless of the cone angle.
                                          float cutoff = coneAngle[i];
                                          float smoothness = (1.0 - cutoff) * 0.5; // Adjust 0.5 for a harder/softer edge.
                                          spotFactor = smoothstep(cutoff, cutoff + smoothness, theta);
                 }
        }

        // 6. Calculate the final diffuse contribution for this light.
        float baseDiffuse = max(dot(n, l), 0.0);
        float diffuseMix = mix(1.0, baseDiffuse, normalInfluence);
        vec3 diffuse = (lightColor[i].rgb * lightColor[i].a) * diffuseMix * attenuation * spotFactor;

        // Add this light's contribution to the total.
        totalLight += diffuse;

        // 6a. If using a specular map, calculate the specular contribution.
        if (u_useSpecularMap) {
            // Read the raw grayscale value from the texture (red channel is used).
            float rawSpecValue = texture2D(u_specular, v_texCoord).r;

            // Apply contrast stretching.
            float specMask = smoothstep(u_specularRemapMin, u_specularRemapMax, rawSpecValue);

            vec3 reflectDir = reflect(-l, n);
            float specAmount = pow(max(dot(viewDir, reflectDir), 0.0), shininess);

            vec3 specular = (lightColor[i].rgb * lightColor[i].a) * specAmount * specMask * attenuation * spotFactor;
            totalSpecular += u_specularIntensity * specular;
        }
    }

    // 7. Calculate the final color by modulating the texture's color with the total light.
    vec3 finalColor = (diffuseColor.rgb * totalLight) + totalSpecular;

    // 7a. Apply the color overlay effect.
    finalColor.rgb = mix(finalColor.rgb, u_overlayColor.rgb, u_overlayStrength);

    // 8. Set the final fragment color, retaining the original texture's alpha.
    gl_FragColor = v_color * vec4(finalColor, diffuseColor.a);
}
