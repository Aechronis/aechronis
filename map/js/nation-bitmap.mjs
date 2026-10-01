export const nationFillUniforms = {
  name: 'nationFill',
  fs: `layout(std140) uniform nationFillUniforms { float fade; } nationFill;`,
  uniformTypes: { fade: 'f32' },
};

// Interpolate only opacity. Filtering the whole bitmap would blur ownership
// edges and, in relations mode, corrupt the encoded nation IDs.
export function smoothNationOpacityShader(fs, channel = 'a') {
  return fs.replace('void main(void) {', `
    float nationAlphaAt(ivec2 pixel, float fallbackAlpha) {
      ivec2 size = textureSize(bitmapTexture, 0);
      vec4 sampleColor = texelFetch(bitmapTexture, clamp(pixel, ivec2(0), size - 1), 0);
      // Missing/attacked chunks must not pull neighboring opacity toward zero.
      return sampleColor.a > 0.0 ? sampleColor.${channel} : fallbackAlpha;
    }
    float smoothNationAlpha(vec2 uv, float fallbackAlpha) {
      vec2 pixel = uv * vec2(textureSize(bitmapTexture, 0)) - 0.5;
      ivec2 base = ivec2(floor(pixel));
      vec2 weight = fract(pixel);
      float alpha = mix(
        mix(nationAlphaAt(base, fallbackAlpha),
            nationAlphaAt(base + ivec2(1, 0), fallbackAlpha), weight.x),
        mix(nationAlphaAt(base + ivec2(0, 1), fallbackAlpha),
            nationAlphaAt(base + ivec2(1, 1), fallbackAlpha), weight.x), weight.y);
      return alpha * (1.0 - nationFill.fade);
    }
    void main(void) {`);
}
