#version 330 core

layout(location = 0) in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 vWorldRay;

void main() {
    // Passa a direção do raio na esfera celeste (sem translação da câmera)
    vWorldRay = Position;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
