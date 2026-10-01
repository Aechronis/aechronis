import { BitmapLayer } from '@deck.gl/layers';
import { smoothNationOpacityShader, nationFillUniforms } from './nation-bitmap.mjs';
import { chunkCanvas } from './data-index.mjs';
import { RELATION_COLORS, nationRelationship } from './nation-relations.mjs';

export function buildRelationPalette(names, nations, source, deathWar) {
  const { buf, commit } = chunkCanvas(256, Math.ceil(names.length / 256));
  names.forEach((name, id) => {
    const color = name ? RELATION_COLORS[nationRelationship(nations, source, name, deathWar)] : [128, 128, 128];
    buf.set([...color, 255], id * 4);
  });
  return commit();
}

export class RelationBitmapLayer extends BitmapLayer {
  static layerName = 'RelationBitmapLayer';
  static defaultProps = {
    palette: { type: 'image', value: null, async: true },
    fillFade: { type: 'number', value: 0, min: 0, max: 1 },
    attackMask: { type: 'object', value: null, compare: false },
  };

  getShaders() {
    const shaders = super.getShaders();
    return { ...shaders, modules: [...shaders.modules, nationFillUniforms], fs: smoothNationOpacityShader(shaders.fs, 'b')
      .replace('uniform sampler2D bitmapTexture;', 'uniform sampler2D bitmapTexture;\nuniform sampler2D relationPalette;\nuniform sampler2D attackMask;')
      .replace('sampleColor.a > 0.0', 'sampleColor.a > 0.0 && texelFetch(attackMask, clamp(pixel, ivec2(0), size - 1), 0).r == 0.0')
      .replace('vec4 bitmapColor = texture(bitmapTexture, uv);', `
        // IDs are categorical: generated mipmaps average them into unrelated
        // palette entries at overview zoom, even with nearest minFilter.
        ivec2 size = textureSize(bitmapTexture, 0);
        ivec2 pixel = clamp(ivec2(uv * vec2(size)), ivec2(0), size - 1);
        vec4 encoded = texelFetch(bitmapTexture, pixel, 0);
        ivec2 paletteIndex = ivec2(round(encoded.rg * 255.0));
        vec4 bitmapColor = vec4(texelFetch(relationPalette, paletteIndex, 0).rgb,
          smoothNationAlpha(uv, encoded.b) * encoded.a * (1.0 - texture(attackMask, uv).r));
      `) };
  }

  draw(opts) {
    if (!this.props.palette) return;
    this.state.model?.shaderInputs.setProps({ nationFill: { fade: this.props.fillFade } });
    this.state.model?.setBindings({ relationPalette: this.props.palette,
      attackMask: this.props.attackMask.getTexture(this.context.device) });
    super.draw(opts);
  }
}
