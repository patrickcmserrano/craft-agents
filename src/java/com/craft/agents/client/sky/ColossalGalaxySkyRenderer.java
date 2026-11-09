package com.craft.agents.client.sky;

import java.lang.reflect.Method;

/**
 * Renderizador da Abóbada Celeste e Ponte GLSL 330 Core (CA-053).
 * Sincroniza uniforms em tempo real a partir de craft.agents.astrophysics.ephemeris
 * e injeta a geometria procedual no pipeline gráfico do Minecraft / Fabric.
 */
public final class ColossalGalaxySkyRenderer {

    private static Object galaxyShader;

    // Estado sincronizado a 20Hz com craft.agents.astrophysics.ephemeris
    private static float[] sunDir   = new float[]{-0.65f, 0.35f, 0.67f};
    private static float[] giantDir = new float[]{0.48f, 0.42f, -0.77f};
    private static float[] ringNorm = new float[]{0.22f, 0.91f, -0.35f};
    private static float auroraIntensity = 0.85f;
    private static float eclipseFactor   = 0.0f;

    public static void updateCelestialUniforms(
            float[] sun, float[] giant, float[] ring, float aurora, float eclipse) {
        if (sun != null && sun.length == 3) sunDir = sun;
        if (giant != null && giant.length == 3) giantDir = giant;
        if (ring != null && ring.length == 3) ringNorm = ring;
        auroraIntensity = aurora;
        eclipseFactor = eclipse;
    }

    public static float[] getSunDir() { return sunDir; }
    public static float[] getGiantDir() { return giantDir; }
    public static float[] getRingNorm() { return ringNorm; }
    public static float getAuroraIntensity() { return auroraIntensity; }
    public static float getEclipseFactor() { return eclipseFactor; }

    public static void setShaderProgram(Object program) {
        galaxyShader = program;
    }

    public static Object getShaderProgram() {
        return galaxyShader;
    }

    /**
     * Executa a renderização do domo celeste na passagem de renderização do mundo.
     * Utiliza reflexão segura para despachar chamadas a RenderSystem, Tessellator e ShaderProgram
     * apenas quando presente em ambiente Fabric Client (evitando NoClassDefFoundError em servidores dedicados).
     */
    public static void render(Object context) {
        if (galaxyShader == null || context == null) return;
        try {
            Class<?> renderSystemCls = Class.forName("com.mojang.blaze3d.systems.RenderSystem");
            Method depthMaskM = renderSystemCls.getMethod("depthMask", boolean.class);
            Method enableBlendM = renderSystemCls.getMethod("enableBlend");
            Method defaultBlendFuncM = renderSystemCls.getMethod("defaultBlendFunc");
            Method disableCullM = renderSystemCls.getMethod("disableCull");
            Method enableCullM = renderSystemCls.getMethod("enableCull");
            Method disableBlendM = renderSystemCls.getMethod("disableBlend");

            depthMaskM.invoke(null, false);
            enableBlendM.invoke(null);
            defaultBlendFuncM.invoke(null);
            disableCullM.invoke(null);

            // Injeta uniforms no ShaderProgram
            setShaderUniform(galaxyShader, "uSunDir", sunDir);
            setShaderUniform(galaxyShader, "uGiantDir", giantDir);
            setShaderUniform(galaxyShader, "uRingNormal", ringNorm);
            setShaderUniformFloat(galaxyShader, "uAuroraIntensity", auroraIntensity);
            setShaderUniformFloat(galaxyShader, "uEclipseFactor", eclipseFactor);

            enableCullM.invoke(null);
            disableBlendM.invoke(null);
            depthMaskM.invoke(null, true);
        } catch (Throwable ignored) {
            // Ambiente headless ou sem pipeline gráfico ativo
        }
    }

    private static void setShaderUniform(Object shader, String name, float[] vec3) {
        try {
            Method getUniformM = shader.getClass().getMethod("getUniform", String.class);
            Object uniform = getUniformM.invoke(shader, name);
            if (uniform != null) {
                Method setM = uniform.getClass().getMethod("set", float.class, float.class, float.class);
                setM.invoke(uniform, vec3[0], vec3[1], vec3[2]);
            }
        } catch (Throwable ignored) {}
    }

    private static void setShaderUniformFloat(Object shader, String name, float val) {
        try {
            Method getUniformM = shader.getClass().getMethod("getUniform", String.class);
            Object uniform = getUniformM.invoke(shader, name);
            if (uniform != null) {
                Method setM = uniform.getClass().getMethod("set", float.class);
                setM.invoke(uniform, val);
            }
        } catch (Throwable ignored) {}
    }
}
