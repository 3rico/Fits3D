#version 330 core

layout(location = 0) in vec3 vertexPosition_modelspace;

uniform float f;
uniform mat4 mvp;

void main() {
	vec4 v = vec4(vertexPosition_modelspace, 1);
    gl_Position = mvp * v;
}