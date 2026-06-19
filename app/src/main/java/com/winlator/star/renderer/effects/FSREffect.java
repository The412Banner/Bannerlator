package com.winlator.star.renderer.effects;

import com.winlator.star.renderer.material.ScreenMaterial;
import com.winlator.star.renderer.material.ShaderMaterial;
import java.util.Locale;

public class FSREffect extends Effect {

    // --- MODOS ---
    public static final int MODE_SUPER_RESOLUTION = 0; // CAS Puro
    public static final int MODE_DLS = 1;              // CAS + Saturação

    private int currentMode = MODE_SUPER_RESOLUTION; 
    private float sharpnessLevel = 1.0f; // Padrão Nivel 1 (Imagem Limpa)

    public void setMode(int mode) {
        this.currentMode = mode;
    }

    public int getMode() { return currentMode; }

    public void setLevel(float level) {
        this.sharpnessLevel = level;
    }
    
    public float getLevel() { return sharpnessLevel; }

    @Override
    protected ShaderMaterial createMaterial() {
        return new FSRMaterial();
    }

    private class FSRMaterial extends ScreenMaterial {
        @Override
        protected String getFragmentShader() {
            // CONFIGURAÇÃO DOS 5 NÍVEIS (Calibragem Fina)
            // AMD Sharpness: quanto menor o numero, mais forte (0.0 = Max, 1.0 = Min)
            float sharpness = 0.0f;
            
            if (sharpnessLevel <= 1.1f) {
                // Nivel 1: Muito Suave (Remove o embaçado do jogo sem criar serrilhado)
                sharpness = 0.90f; 
            } else if (sharpnessLevel <= 2.1f) {
                // Nivel 2: Nitidez Leve
                sharpness = 0.70f; 
            } else if (sharpnessLevel <= 3.1f) {
                // Nivel 3: Equilibrado
                sharpness = 0.50f; 
            } else if (sharpnessLevel <= 4.1f) {
                // Nivel 4: Forte
                sharpness = 0.30f; 
            } else {
                // Nivel 5: Extremo (Corrigido)
                // Antes era 0.0 (quebrava a imagem). Agora é 0.12 (Nitidez Perfeita).
                sharpness = 0.12f; 
            }

            // Se for DLS, aplica cor extra (15%) em todos os niveis
            boolean useDLS = (currentMode == MODE_DLS);
            float saturation = useDLS ? 1.15f : 1.0f;

            String sSharpness = String.format(Locale.US, "%.4f", sharpness);
            String sSat = String.format(Locale.US, "%.4f", saturation);
            
            StringBuilder shader = new StringBuilder();
            
            shader.append("precision mediump float;\n");
            shader.append("uniform sampler2D screenTexture;\n");
            shader.append("uniform vec2 resolution;\n");
            
            shader.append("const float SHARPNESS = ").append(sSharpness).append(";\n");
            if (useDLS) {
                shader.append("const float SAT = ").append(sSat).append(";\n");
            }

            shader.append("void main() {\n");
            shader.append("    vec2 uv = gl_FragCoord.xy / resolution;\n");
            shader.append("    vec2 px = 1.0 / resolution;\n");
            
            // Leitura 3x3
            shader.append("    vec3 a = texture2D(screenTexture, uv + vec2(-px.x, -px.y)).rgb;\n");
            shader.append("    vec3 b = texture2D(screenTexture, uv + vec2( 0.0,  -px.y)).rgb;\n");
            shader.append("    vec3 c = texture2D(screenTexture, uv + vec2( px.x, -px.y)).rgb;\n");
            shader.append("    vec3 d = texture2D(screenTexture, uv + vec2(-px.x,  0.0)).rgb;\n");
            shader.append("    vec3 e = texture2D(screenTexture, uv).rgb;\n"); 
            shader.append("    vec3 f = texture2D(screenTexture, uv + vec2( px.x,  0.0)).rgb;\n");
            shader.append("    vec3 g = texture2D(screenTexture, uv + vec2(-px.x,  px.y)).rgb;\n");
            shader.append("    vec3 h = texture2D(screenTexture, uv + vec2( 0.0,   px.y)).rgb;\n");
            shader.append("    vec3 i = texture2D(screenTexture, uv + vec2( px.x,  px.y)).rgb;\n");
            
            // Algoritmo CAS
            shader.append("    vec3 mnRGB  = min(min(min(d,e),min(f,b)),h);\n");
            shader.append("    vec3 mnRGB2 = min(min(min(mnRGB,a),min(g,c)),i);\n");
            shader.append("    mnRGB += mnRGB2;\n");

            shader.append("    vec3 mxRGB  = max(max(max(d,e),max(f,b)),h);\n");
            shader.append("    vec3 mxRGB2 = max(max(max(mxRGB,a),max(g,c)),i);\n");
            shader.append("    mxRGB += mxRGB2;\n");

            shader.append("    vec3 rcpMxRGB = vec3(1.0) / mxRGB;\n");
            shader.append("    vec3 ampRGB = clamp((min(mnRGB, 2.0 - mxRGB) * rcpMxRGB), 0.0, 1.0);\n");
            
            shader.append("    ampRGB = inversesqrt(ampRGB);\n");
            shader.append("    float peak = 8.0 - 3.0 * SHARPNESS;\n");
            shader.append("    vec3 wRGB = -vec3(1.0) / (ampRGB * peak);\n");
            shader.append("    vec3 rcpWeightRGB = vec3(1.0) / (1.0 + 4.0 * wRGB);\n");

            shader.append("    vec3 window = (b + d) + (f + h);\n");
            shader.append("    vec3 outColor = clamp((window * wRGB + e) * rcpWeightRGB, 0.0, 1.0);\n");
            
            if (useDLS) {
                // Modo DLS: Aplica Cor
                shader.append("    float luma = dot(outColor, vec3(0.299, 0.587, 0.114));\n");
                shader.append("    outColor = mix(vec3(luma), outColor, SAT);\n");
            } 

            shader.append("    gl_FragColor = vec4(clamp(outColor, 0.0, 1.0), 1.0);\n");
            shader.append("}");

            return shader.toString();
        }
    }
}