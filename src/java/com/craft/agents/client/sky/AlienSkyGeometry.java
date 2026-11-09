package com.craft.agents.client.sky;

import java.util.Random;

/**
 * Geometria Fotométrica e Astrofísica Analítica para o SkyRenderer (CA-051).
 * Implementa conversão de Corpo Negro de Planck, catálogo estelar determinístico
 * e interseção analítica Raio-Plano para auto-sombreamento dos anéis do Gigante Gasoso.
 */
public final class AlienSkyGeometry {
    private static final long STARFIELD_SEED = 0x5F3759DFCAFEBABEL;

    public record StarVertex(float x, float y, float z, float r, float g, float b, float size, float twinklePhase) {}

    /**
     * Converte Temperatura Efetiva Estelar (Kelvin: 2800K a 30000K) em RGB normalizado [0.0 .. 1.0]
     * segundo a aproximação analítica de Planck (Diagrama Hertzsprung-Russell).
     */
    public static float[] kelvinToRgb(double kelvin) {
        double temp = Math.max(1000.0, Math.min(40000.0, kelvin)) / 100.0;
        double r, g, b;

        if (temp <= 66.0) {
            r = 255.0;
            g = 99.4708025861 * Math.log(temp) - 161.1195681661;
            if (temp <= 19.0) {
                b = 0.0;
            } else {
                double rawB = 138.5177312231 * Math.log(temp - 10.0) - 305.0447927307;
                // Atenuação de bandas moleculares de TiO em anãs vermelhas frias (Classe M: temp <= 32.0)
                b = (temp <= 32.0) ? rawB * 0.82 : rawB;
            }
        } else {
            r = 329.698727446 * Math.pow(temp - 60.0, -0.1332047592);
            g = 288.1221695283 * Math.pow(temp - 60.0, -0.0755148492);
            b = 255.0;
        }
        return new float[]{
            (float) Math.clamp(r / 255.0, 0.0, 1.0),
            (float) Math.clamp(g / 255.0, 0.0, 1.0),
            (float) Math.clamp(b / 255.0, 0.0, 1.0)
        };
    }

    /**
     * Gera catálogo estelar determinístico com distribuição no plano galáctico
     * e pesos espectrais de Salpeter (Classes M, K, G, F, A, B, O).
     */
    public static StarVertex[] generatePhotometricCatalog(int count) {
        Random rng = new Random(STARFIELD_SEED);
        StarVertex[] stars = new StarVertex[count];

        for (int i = 0; i < count; i++) {
            boolean inGalacticPlane = rng.nextDouble() < 0.70;
            double lon = rng.nextDouble() * 2.0 * Math.PI;
            double lat = inGalacticPlane
                ? Math.clamp(rng.nextGaussian() * 0.18, -Math.PI / 2.0, Math.PI / 2.0)
                : Math.asin(2.0 * rng.nextDouble() - 1.0);

            float x = (float) (Math.cos(lat) * Math.cos(lon));
            float y = (float) Math.sin(lat);
            float z = (float) (Math.cos(lat) * Math.sin(lon));

            // Amostragem de Temperatura (Classes M/K rubi/âmbar a O/B ciano)
            double u = rng.nextDouble();
            double kelvin;
            if (u < 0.55)      kelvin = 2800.0 + rng.nextDouble() * 1700.0;
            else if (u < 0.82) kelvin = 4500.0 + rng.nextDouble() * 2000.0;
            else if (u < 0.95) kelvin = 6500.0 + rng.nextDouble() * 4500.0;
            else               kelvin = 11000.0 + rng.nextDouble() * 19000.0;

            float[] rgb = kelvinToRgb(kelvin);
            float size = (float) (0.08 + Math.pow(rng.nextDouble(), 4.0) * 0.42);
            float phase = (float) (rng.nextDouble() * 2.0 * Math.PI);

            stars[i] = new StarVertex(x, y, z, rgb[0], rgb[1], rgb[2], size, phase);
        }
        return stars;
    }

    /**
     * Interseção Raio-Plano analítica para calcular o fator de sombra dos anéis sobre a superfície do planeta.
     * Retorna 1.0 (iluminação total) a 0.25 (sombra densa das divisões de anéis).
     */
    public static float computeRingShadowOnPlanet(float[] surfacePos, float[] lightDir, float[] ringN, float innerR, float outerR) {
        float denom = lightDir[0] * ringN[0] + lightDir[1] * ringN[1] + lightDir[2] * ringN[2];
        if (Math.abs(denom) < 1.0e-5f) return 1.0f;

        float dotSurfaceN = surfacePos[0] * ringN[0] + surfacePos[1] * ringN[1] + surfacePos[2] * ringN[2];
        float t = -dotSurfaceN / denom;
        if (t <= 0.0f) return 1.0f;

        float hx = surfacePos[0] + lightDir[0] * t;
        float hy = surfacePos[1] + lightDir[1] * t;
        float hz = surfacePos[2] + lightDir[2] * t;
        float dist = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);

        if (dist >= innerR && dist <= outerR) {
            float normDist = (dist - innerR) / (outerR - innerR);
            float bands = (float) (Math.sin(normDist * 45.0) * 0.5 + 0.5);
            float fineBands = (float) (Math.sin(normDist * 180.0) * 0.5 + 0.5);
            float opticalDepth = (bands * 0.7f + fineBands * 0.3f);
            return 1.0f - opticalDepth * 0.75f;
        }
        return 1.0f;
    }
}
