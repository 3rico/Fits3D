#version 330 core

in float val;
in float shade;

out vec4 color;
uniform float filterMinX;
uniform float filterMaxX;
uniform float filterGradient;
uniform float filterConstant;

uniform float alphaFudge;
uniform float pointArea;  
uniform vec4 pointColor;

uniform int isSelecting;

  
void main(){
	if (val < filterMinX || val > filterMaxX) {
		discard;
	} 
	float alpha = val * filterGradient + filterConstant;
	if (isSelecting == 0 || shade < 1.0f) {
		alpha = alpha * alphaFudge;
	}
	alpha = alpha * shade;
    color = vec4(pointColor[0], pointColor[1], pointColor[2], alpha * min(pointArea, 1.0));
    //TODO replace min(pointArea, 1.0) with code in renderer, no point in doing this for each fragment you dope.
}