#version 330 core

in vec3 vWorldRay;
out vec4 FragColor;

uniform float uTime;             // (worldTime + tickDelta) * 0.001
uniform vec3  uSunDir;           // Vetor unitário da estrela principal (ephemeris.clj)
uniform vec3  uGiantDir;         // Vetor unitário do Gigante Gasoso (ephemeris.clj)
uniform vec3  uRingNormal;       // Normal inclinada do plano dos anéis
uniform float uAuroraIntensity;  // [0.0 .. 1.0] vindo do SceneDigest
uniform float uEclipseFactor;    // [0.0 .. 1.0] obscuração solar

const float PI = 3.14159265359;

// ============================================================================
// 1. PRIMITIVAS DE RUÍDO FRACTAL (Simplex / Value Noise 3D + fBm + Domain Warp)
// ============================================================================
float hash31(vec3 p) {
    p = fract(p * vec3(443.897, 441.423, 437.195));
    p += dot(p, p.yzx + 19.19);
    return fract((p.x + p.y) * p.z);
}

float noise3D(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    return mix(
        mix(mix(hash31(i + vec3(0,0,0)), hash31(i + vec3(1,0,0)), f.x),
            mix(hash31(i + vec3(0,1,0)), hash31(i + vec3(1,1,0)), f.x), f.y),
        mix(mix(hash31(i + vec3(0,0,1)), hash31(i + vec3(1,0,1)), f.x),
            mix(hash31(i + vec3(0,1,1)), hash31(i + vec3(1,1,1)), f.x), f.y),
        f.z
    );
}

// Fractal Brownian Motion de 5 oitavas com rotação entre camadas
float fbm(vec3 p) {
    float v = 0.0;
    float amp = 0.5;
    mat3 rot = mat3(0.00,  0.80,  0.60,
                   -0.80,  0.36, -0.48,
                   -0.60, -0.48,  0.64);
    for (int i = 0; i < 5; i++) {
        v += amp * noise3D(p);
        p = rot * p * 2.03 + vec3(13.7, 7.1, 3.3);
        amp *= 0.5;
    }
    return v;
}

// ============================================================================
// 2. CAMPO ESTELAR FOTOMÉTRICO (Diagrama HR + Cintilação Kasten-Young)
// ============================================================================
vec3 blackbodyColor(float h) {
    // Classes M/K (Rubi/Âmbar) -> G/F (Amarelo/Branco) -> O/B (Azul-Ciano)
    if (h < 0.55) return mix(vec3(1.0, 0.38, 0.18), vec3(1.0, 0.72, 0.45), h / 0.55);
    if (h < 0.85) return mix(vec3(1.0, 0.72, 0.45), vec3(0.95, 0.97, 1.0), (h - 0.55) / 0.30);
    return mix(vec3(0.95, 0.97, 1.0), vec3(0.45, 0.75, 1.0), (h - 0.85) / 0.15);
}

vec3 renderPhotometricStarfield(vec3 rd) {
    vec3 stars = vec3(0.0);
    // 2 camadas de resolução espacial para estrelas de fundo e supergigantes de 1ª grandeza
    for (int layer = 0; layer < 2; layer++) {
        float scale = (layer == 0) ? 95.0 : 42.0;
        vec3 p = rd * scale;
        vec3 id = floor(p);
        vec3 f = fract(p) - 0.5;

        float rnd = hash31(id + float(layer) * 71.3);
        if (rnd > 0.65) {
            vec3 offset = (vec3(hash31(id + 1.0), hash31(id + 2.0), hash31(id + 3.0)) - 0.5) * 0.65;
            float d = length(f - offset);
            float mag = pow((rnd - 0.65) / 0.35, 3.0);
            float radius = (layer == 0) ? 0.045 : 0.085;

            // Cintilação mais rápida perto do horizonte (rd.y próximo de 0)
            float horizonFactor = clamp(1.0 - rd.y, 0.2, 1.0);
            float twinkle = 0.75 + 0.25 * sin(uTime * 18.0 * horizonFactor + rnd * 62.8);

            float core = smoothstep(radius, 0.0, d) * mag * twinkle;
            float halo = exp(-d * ((layer == 0) ? 18.0 : 9.0)) * mag * 0.45;
            vec3 col = blackbodyColor(hash31(id + 9.7));
            stars += col * (core * 2.2 + halo);
        }
    }
    return stars;
}

// ============================================================================
// 3. GALÁXIA ESPIRAL COLOSSAL (Braços Logarítmicos + Poeira + H-Alpha 656nm)
// ============================================================================
vec3 renderColossalGalaxy(vec3 rd) {
    // Sistema de coordenadas inclinado da Galáxia Colossal no céu (42° de inclinação)
    vec3 galCenter = normalize(vec3(-0.45, 0.58, -0.67));
    vec3 galUp     = normalize(vec3(0.52, 0.74, 0.42));
    vec3 galRight  = normalize(cross(galCenter, galUp));
    vec3 galFwd    = cross(galUp, galRight);

    // Projeta o raio de visão no plano do disco galáctico
    float planeDenom = dot(rd, galUp);
    // Coordenadas locais no plano tangente da galáxia
    float gx = dot(rd - galCenter, galRight) * 2.1;
    float gy = dot(rd - galCenter, galFwd) * 0.85; // Efeito de perspectiva (disco inclinado)
    float gz = planeDenom;

    float r = length(vec2(gx, gy));
    if (r > 1.65) return vec3(0.0);

    float theta = atan(gy, gx) + uTime * 0.015;

    // Equação dos 2 braços espirais logarítmicos principais + 2 sub-braços
    float twist = 4.2 * log(max(r, 0.025));
    float armPhase1 = cos(2.0 * (theta - twist));
    float armPhase2 = cos(4.0 * (theta - twist * 0.85) + 1.2);

    // Domain warping com fBm para criar filamentos turbulentos
    vec3 noiseCoord = vec3(gx * 3.5, gy * 3.5, theta * 0.5);
    float warp = fbm(noiseCoord + fbm(noiseCoord * 1.8));

    float armDensity = smoothstep(-0.3, 0.85, armPhase1 * 0.7 + armPhase2 * 0.3 + (warp - 0.5) * 0.9);
    float radialFalloff = exp(-r * 2.35) * (1.0 - smoothstep(1.1, 1.6, r));
    float diskEnvelope = armDensity * radialFalloff;

    // Bulbo Galáctico Central (Núcleo super-brilhante amarelo-dourado / branco quente)
    float bulge = exp(-r * 7.5) * 1.65 + exp(-r * 2.8) * 0.45;
    vec3 bulgeColor = vec3(1.0, 0.88, 0.68) * bulge;

    // Aglomerados Azuis OB (estrelas jovens nas cristas dos braços)
    vec3 armStarColor = mix(vec3(0.28, 0.58, 1.0), vec3(0.65, 0.42, 0.95), warp) * diskEnvelope * 1.35;

    // Regiões H-II de Hidrogênio-Alfa (656.3 nm — Rosa/Magenta incandescente nos nós dos braços)
    float hAlphaNoise = smoothstep(0.54, 0.78, fbm(vec3(gx * 8.0, gy * 8.0, 2.1)));
    float hAlphaZones = hAlphaNoise * smoothstep(0.2, 0.9, armPhase1) * radialFalloff;
    vec3 hAlphaColor  = vec3(1.0, 0.18, 0.55) * hAlphaZones * 2.4;

    // Faixas de Poeira Escura Interestelar (Absorção de Beer-Lambert cortando o núcleo e os braços)
    float dustRidge = 1.0 - abs(2.0 * fbm(vec3(gx * 5.2 + warp * 0.4, gy * 5.2 - warp * 0.4, 4.7)) - 1.0);
    float dustMask  = smoothstep(0.55, 0.92, pow(dustRidge, 2.2)) * exp(-r * 1.4);
    float transmittance = mix(1.0, 0.12, dustMask); // Retém até 88% da luz atrás da poeira

    vec3 galaxyRgb = (bulgeColor + armStarColor + hAlphaColor) * transmittance;
    // Borda quente de espalhamento na poeira cósmica
    galaxyRgb += vec3(0.45, 0.22, 0.35) * dustMask * bulge * 0.35;

    return max(galaxyRgb, vec3(0.0));
}

// ============================================================================
// 4. AURORAS VOLUMÉTRICAS EM CORTINAS (Raymarching na Termosfera)
// ============================================================================
vec3 renderVolumetricAurora(vec3 rd) {
    if (rd.y <= 0.02 || uAuroraIntensity <= 0.01) return vec3(0.0);

    vec3 accColor = vec3(0.0);
    const int STEPS = 14;
    for (int i = 0; i < STEPS; i++) {
        float fi = float(i) / float(STEPS);
        float height = 1.0 + fi * 0.85;
        // Projeta na camada esférica superior
        vec2 uv = rd.xz / (rd.y + 0.18) * height;

        // Cortinas senoidais dobradas por fBm
        float wave = sin(uv.x * 3.2 + uTime * 0.35 + fbm(vec3(uv * 1.4, uTime * 0.1)) * 4.5);
        float curtain = exp(-abs(wave) * 5.5) * smoothstep(0.02, 0.35, rd.y) * (1.0 - smoothstep(0.55, 0.95, rd.y));

        // Emissão atômica: Verde Oxigênio (557.7nm) na base -> Violeta/Magenta Nitrogênio no topo
        vec3 auroraTint = mix(vec3(0.10, 0.95, 0.52), vec3(0.68, 0.22, 0.95), fi);
        accColor += auroraTint * curtain * (1.0 - fi * 0.5) * 0.14;
    }
    return accColor * uAuroraIntensity;
}

// ============================================================================
// 5. GIGANTE GASOSO + ANÉIS COM AUTO-SOMBREAMENTO ANALÍTICO (Raio-Esfera/Plano)
// ============================================================================
float ringShadowAtPoint(vec3 localPos, vec3 lightDir, vec3 ringN, float rInner, float rOuter) {
    float denom = dot(lightDir, ringN);
    if (abs(denom) < 1e-4) return 1.0;
    float t = -dot(localPos, ringN) / denom;
    if (t <= 0.0) return 1.0;

    vec3 hit = localPos + lightDir * t;
    float d = length(hit);
    if (d >= rInner && d <= rOuter) {
        float u = (d - rInner) / (rOuter - rInner);
        float cassini = smoothstep(0.05, 0.12, abs(u - 0.58)); // Divisão de Cassini
        float rings = (0.5 + 0.5 * sin(u * 52.0)) * (0.5 + 0.5 * sin(u * 190.0)) * cassini;
        return mix(1.0, 0.18, rings);
    }
    return 1.0;
}

vec4 renderGasGiantAndRings(vec3 rd) {
    // Posiciona o Gigante Gasoso a uma distância virtual D = 4.0 com raio R = 0.62 (~8.8° aparente)
    vec3 center = normalize(uGiantDir) * 4.0;
    float planetR = 0.62;
    float rInner  = 0.86;
    float rOuter  = 1.58;
    vec3 ringN    = normalize(uRingNormal);
    vec3 L        = normalize(uSunDir);

    // 1. Interseção Raio-Esfera (Planeta)
    vec3 oc = -center; // Origem da câmera = (0,0,0)
    float b = dot(oc, rd);
    float c = dot(oc, oc) - planetR * planetR;
    float h = b * b - c;
    float tPlanet = (h > 0.0) ? (-b - sqrt(h)) : 1e9;
    if (tPlanet <= 0.0) tPlanet = 1e9;

    // 2. Interseção Raio-Plano (Sistema de Anéis)
    float denom = dot(rd, ringN);
    float tRing = 1e9;
    vec4 ringColor = vec4(0.0);

    if (abs(denom) > 1e-4) {
        float tr = dot(center, ringN) / denom;
        if (tr > 0.0) {
            vec3 hitLocal = (rd * tr) - center;
            float dist = length(hitLocal);
            if (dist >= rInner && dist <= rOuter) {
                tRing = tr;
                float u = (dist - rInner) / (rOuter - rInner);
                float cassini = smoothstep(0.03, 0.08, abs(u - 0.58));
                float bands = (0.55 + 0.45 * sin(u * 55.0)) * (0.6 + 0.4 * cos(u * 175.0));
                float alpha = bands * cassini * smoothstep(0.0, 0.08, u) * (1.0 - smoothstep(0.88, 1.0, u)) * 0.85;

                // Sombra cilíndrica do próprio Gigante Gasoso projetada sobre os anéis!
                float projOnSun = dot(hitLocal, L);
                float distToShadowAxis = length(hitLocal - projOnSun * L);
                float planetShadow = (projOnSun < 0.0) ? smoothstep(planetR * 0.94, planetR * 1.03, distToShadowAxis) : 1.0;

                vec3 rCol = mix(vec3(0.55, 0.64, 0.75), vec3(0.85, 0.78, 0.68), bands) * (0.08 + 0.92 * planetShadow);
                ringColor = vec4(rCol, alpha);
            }
        }
    }

    // 3. Renderização da Atmosfera de Bandas do Gigante Gasoso
    vec4 planetColor = vec4(0.0);
    if (tPlanet < 1e8) {
        vec3 hitPos   = rd * tPlanet;
        vec3 localPos = hitPos - center;
        vec3 N        = normalize(localPos);

        // Latitude e turbulência zonal (Júpiter/Saturno alienígena)
        float lat = asin(clamp(N.y, -1.0, 1.0));
        float lon = atan(N.z, N.x) + uTime * 0.08;
        float zonalWarp = fbm(vec3(N * 4.5 + vec3(uTime * 0.05, 0.0, 0.0)));
        float bandPattern = sin(lat * 18.0 + zonalWarp * 3.2);

        vec3 cloudAlb = mix(vec3(0.22, 0.36, 0.52), vec3(0.82, 0.64, 0.48), smoothstep(-0.7, 0.7, bandPattern));
        cloudAlb = mix(cloudAlb, vec3(0.45, 0.78, 0.85), smoothstep(0.55, 0.85, zonalWarp));

        // Terminador + Sombra dos Anéis sobre as Nuvens
        float NdotL = dot(N, L);
        float daylight = smoothstep(-0.15, 0.25, NdotL);
        float rShadow  = ringShadowAtPoint(localPos, L, ringN, rInner, rOuter);

        // Fresnel / Atmospheric Rim ciano brilhante
        float fresnel = pow(1.0 - max(dot(-rd, N), 0.0), 3.2);
        vec3 rimGlow  = vec3(0.25, 0.82, 1.0) * fresnel * smoothstep(-0.35, 0.45, NdotL) * 1.4;

        vec3 darkSide = cloudAlb * vec3(0.03, 0.05, 0.09);
        vec3 finalPlanet = mix(darkSide, cloudAlb, daylight * rShadow) + rimGlow;
        planetColor = vec4(finalPlanet, 1.0);
    }

    // Ordenação de profundidade exata: Anel na frente do planeta vs. Planeta na frente do anel
    if (tPlanet < tRing) {
        return planetColor;
    } else if (tRing < 1e8) {
        vec3 comp = mix(planetColor.rgb, ringColor.rgb, ringColor.a);
        float compA = max(planetColor.a, ringColor.a);
        return vec4(comp, compA);
    }
    return vec4(0.0);
}

// ============================================================================
// 6. COMPOSIÇÃO FINAL DA ABÓBADA CELESTE
// ============================================================================
void main() {
    vec3 rd = normalize(vWorldRay);

    // 1. Gradiente atmosférico de fundo (Rayleigh noturno + Crepúsculo no horizonte)
    float horizon = exp(-max(rd.y, 0.0) * 5.5);
    vec3 zenithCol  = vec3(0.02, 0.04, 0.09);
    vec3 horizonCol = mix(vec3(0.22, 0.14, 0.28), vec3(0.65, 0.35, 0.25), horizon * 0.65);
    vec3 skyColor   = mix(zenithCol, horizonCol, horizon);

    // 2. Campo Estelar HR + Galáxia Espiral Colossal + Auroras (visíveis acima do horizonte)
    if (rd.y > -0.05) {
        float horizonFade = smoothstep(-0.05, 0.08, rd.y);
        skyColor += renderPhotometricStarfield(rd) * horizonFade;
        skyColor += renderColossalGalaxy(rd) * horizonFade;
        skyColor += renderVolumetricAurora(rd) * horizonFade;
    }

    // 3. Gigante Gasoso e Sistema de Anéis (oculta estrelas e galáxia atrás dele)
    vec4 giantLayer = renderGasGiantAndRings(rd);
    skyColor = mix(skyColor, giantLayer.rgb, giantLayer.a * smoothstep(-0.08, 0.04, rd.y));

    // 4. Tonemapping ACES Filmic para preservar HDR da galáxia sem estourar o branco
    skyColor = (skyColor * (2.51 * skyColor + 0.03)) / (skyColor * (2.43 * skyColor + 0.59) + 0.14);

    FragColor = vec4(skyColor, 1.0);
}
