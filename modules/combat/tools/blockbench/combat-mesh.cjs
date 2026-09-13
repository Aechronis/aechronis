'use strict';

// Rest-pose conversion and byte-exact shader metadata. No editor globals or processes.
const png = require('pngjs').PNG.sync;
const ROLES = ['body', 'magazine', 'bolt', 'dominant_hand', 'support_hand'];
const RIG_FIELDS = ['magazine', 'dominant_hand', 'support_hand', 'sight', 'hipRoot', 'muzzle'];
const FACE_CORNERS = {
    down: [[0,0,1],[0,0,0],[1,0,0],[1,0,1]], up: [[0,1,0],[0,1,1],[1,1,1],[1,1,0]],
    north: [[1,1,0],[1,0,0],[0,0,0],[0,1,0]], south: [[0,1,1],[0,0,1],[1,0,1],[1,1,1]],
    west: [[0,1,0],[0,0,0],[0,0,1],[0,1,1]], east: [[1,1,1],[1,0,1],[1,0,0],[1,1,0]]
};
const clone = value => JSON.parse(JSON.stringify(value));
const fail = message => { throw new Error(message); };
function vector(value, size = 3) {
    if (!Array.isArray(value) || value.length !== size || value.some(n => typeof n !== 'number' || !Number.isFinite(n) || Math.abs(n) > 10000)) {
        fail(`Expected ${size} finite coordinates`);
    }
    return value;
}
// Python's ties-to-even rule is part of the existing packed-coordinate protocol.
function round(value) {
    const lower = Math.floor(value), fraction = value - lower;
    return fraction === .5 ? lower + (Math.abs(lower % 2)) : Math.round(value);
}
function bitmap(source) {
    if (typeof source !== 'string' || !/^data:image\/png;base64,(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(source)) {
        fail('Embed every texture in the .bbmodel before building (PNG required).');
    }
    const bytes = Buffer.from(source.slice(source.indexOf(',') + 1), 'base64');
    if (bytes.length < 33 || !bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])) ||
        bytes.toString('ascii', 12, 16) !== 'IHDR' || bytes.readUInt32BE(16) < 1 || bytes.readUInt32BE(20) < 1 ||
        bytes.readUInt32BE(16) > 1024 || bytes.readUInt32BE(20) > 1536) {
        fail('Textures must be PNGs at most 1024 pixels wide and 1536 pixels high.');
    }
    return png.read(bytes);
}
function image(width, height) { return {width, height, data: Buffer.alloc(width * height * 4)}; }
function paste(target, source, x, y) {
    for (let row = 0; row < source.height; row++) {
        source.data.copy(target.data, ((y + row) * target.width + x) * 4, row * source.width * 4, (row + 1) * source.width * 4);
    }
}
function pixel(target, x, y, color) {
    if (x < 0 || x >= target.width || y < 0 || y >= target.height) fail('Atlas metadata exceeds the allocated image.');
    target.data.set(color, (y * target.width + x) * 4);
}
function rotate([x,y,z], axis, degrees) {
    const c = Math.cos(degrees * Math.PI / 180), s = Math.sin(degrees * Math.PI / 180);
    if (axis === 'x') return [x, c*y-s*z, s*y+c*z];
    if (axis === 'y') return [c*x+s*z, y, -s*x+c*z];
    if (axis === 'z') return [c*x-s*y, s*x+c*y, z];
    fail(`Unsupported rotation axis: ${axis}`);
}
function transformVertex(position, element) {
    const rotation = element.rotation;
    if (!rotation) return position;
    const origin = vector(rotation.origin);
    let local = position.map((n, i) => n - origin[i]);
    if (!rotation.axis) {
        for (const axis of ['z', 'y', 'x']) local = rotate(local, axis, rotation[axis] || 0);
    } else {
        if (rotation.rescale) local = local.map((n, i) => n / Math.max(...rotate([0,1,2].map(j => +(i === j)), rotation.axis, rotation.angle).map(Math.abs)));
        local = rotate(local, rotation.axis, rotation.angle);
    }
    return vector(local.map((n, i) => n + origin[i]));
}
function faceNormal(points) {
    const a = points[1].map((n,i) => n-points[0][i]), b = points[2].map((n,i) => n-points[0][i]);
    const cross = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]];
    const length = Math.sqrt(cross.reduce((sum,n) => sum+n*n, 0));
    return length < 1e-12 ? [0,0,-1] : cross.map(n => n/length);
}
function javaFace(element, direction, face, settings, region) {
    if (!FACE_CORNERS[direction]) fail(`Unknown Java face: ${direction}`);
    const bounds = [vector(element.from), vector(element.to)];
    const positions = FACE_CORNERS[direction].map(corner =>
        transformVertex(corner.map((n,i) => bounds[n][i]), element).map((n,i) =>
            n * settings.scale * (settings.reverse && i !== 1 ? -1 : 1) + settings.offset[i]));
    const [x0,y0,z0] = bounds[0], [x1,y1,z1] = bounds[1];
    const defaults = {down:[x0,16-z1,x1,16-z0], up:[x0,z0,x1,z1], north:[16-x1,16-y1,16-x0,16-y0],
        south:[x0,16-y1,x1,16-y0], west:[z0,16-y1,z1,16-y0], east:[16-z1,16-y1,16-z0,16-y0]};
    const [u0,v0,u1,v1] = vector(face.uv || defaults[direction], 4), turn = face.rotation || 0;
    if (!Number.isFinite(turn) || turn % 90) fail('Face UV rotation must be a multiple of 90 degrees');
    const uvs = [0,1,2,3].map(i => {
        const j = ((i + turn/90) % 4 + 4) % 4;
        return [region[0] + (j < 2 ? u0 : u1)*region[2]/16, region[1] + ([0,3].includes(j) ? v0 : v1)*region[3]/16];
    });
    return {positions, normal: faceNormal(positions), uvs};
}
function settingsFor(model, base) {
    const settings = clone(base), rig = model.aechronis_combat.rig || {};
    if (settings.hold_open !== undefined && typeof settings.hold_open !== 'boolean') fail('Hold-open setting must be a boolean.');
    for (const [key,value] of Object.entries(rig)) {
        if (!RIG_FIELDS.includes(key)) fail(`Unsupported rig setting: ${key}`);
        settings[key] = vector(value, key === 'sight' ? 2 : 3);
    }
    const groups = Object.fromEntries(model.groups.map(g => [g.aechronis_combat_role, g]));
    for (const key of ['magazine', 'dominant_hand', 'support_hand']) settings[key] = vector(groups[key].origin);
    if (!settings.muzzle) fail('Place the muzzle marker using Rig setup before building.');
    return settings;
}
function projectSource(model, base) {
    const settings = settingsFor(model, base), groups = new Map(model.groups.map(g => [g.uuid, g])), parents = new Map();
    function walk(nodes, parent = null) {
        for (const node of nodes) {
            const key = typeof node === 'string' ? node : node.uuid;
            if (parents.has(key)) fail('Duplicate node in the project hierarchy');
            parents.set(key, parent);
            if (typeof node !== 'string') walk(node.children || [], key);
        }
    }
    walk(model.outliner);
    const textures = [], regions = new Map();
    let row = 512;
    for (const [index,texture] of (model.textures || []).entries()) {
        const decoded = bitmap(texture.source);
        if (row + decoded.height > 2047) fail('Combined texture height exceeds the shader UV range. Combine or reduce textures.');
        const region = [0,row,decoded.width,decoded.height,texture.uv_width ?? model.resolution.width,texture.uv_height ?? model.resolution.height];
        if (!region.slice(4).every(n => typeof n === 'number' && Number.isFinite(n) && n > 0)) fail('Texture UV resolution must be positive');
        regions.set(texture.uuid, region); regions.set(String(index), region);
        textures.push([decoded,row]); row += decoded.height;
    }
    if (!textures.length) fail('The project has no embedded textures');
    const artwork = image(1024,row);
    for (const [decoded,offset] of textures) paste(artwork,decoded,0,offset);
    const faces = [];
    for (const [index,element] of (model.elements || []).entries()) {
        if (element.export === false || element.aechronis_combat_marker) continue;
        const role = groups.get(parents.get(element.uuid))?.aechronis_combat_role;
        if (ROLES.slice(3).includes(role)) fail('Hand targets may contain helpers only; move gun geometry to Gun, Magazine, or Bolt.');
        if (!ROLES.slice(0,3).includes(role)) fail(`${element.name}: assign geometry directly to Gun, Magazine, or Bolt.`);
        const origin = vector(element.origin || [0,0,0]), rotation = vector(element.rotation || [0,0,0]);
        let transform = {rotation: {origin, x: rotation[0], y: rotation[1], z: rotation[2]}};
        const kind = element.type || 'cube';
        if (kind === 'cube' && element.box_uv) fail('Use per-face UVs for cubes before building.');
        if (!['cube','mesh'].includes(kind)) fail(`Unsupported geometry type: ${kind}`);
        for (const [name,face] of Object.entries(element.faces || {})) {
            if (face.texture == null || face.texture === false) continue;
            const region = regions.get(String(face.texture));
            if (!region) fail(`${element.name}: a face has an unresolved texture`);
            let positions, uvs;
            if (kind === 'mesh') {
                const keys = face.vertices;
                if (![3,4].includes(keys.length)) fail('Mesh faces must be triangles or quads. Triangulate larger polygons before building.');
                positions = keys.map(key => vector(element.vertices[key]).map((n,i) => n + origin[i]));
                uvs = keys.map(key => vector(face.uv[key], 2));
                if (keys.length === 3) { positions.push(positions[2].slice()); uvs.push(uvs[2].slice()); }
            } else {
                const inflate = element.inflate || 0; vector([inflate,0,0]);
                const bounds = [vector(element.from).map(n => n-inflate), vector(element.to).map(n => n+inflate)];
                if (!FACE_CORNERS[name]) fail(`Unknown cube face: ${name}`);
                positions = FACE_CORNERS[name].map(c => c.map((n,i) => bounds[n][i]));
                const [u0,v0,u1,v1] = vector(face.uv,4), turn = face.rotation || 0;
                if (!Number.isFinite(turn) || turn % 90) fail('Cube UV rotation must be a multiple of 90 degrees');
                uvs = [0,1,2,3].map(i => { const j = ((i+turn/90)%4+4)%4; return [j < 2 ? u0 : u1, [0,3].includes(j) ? v0 : v1]; });
                if (element.rescale && rotation.some(Boolean)) {
                    const axes = [0,1,2].filter(i => rotation[i]);
                    if (axes.length !== 1) fail('Rescaled cubes must rotate around a single axis');
                    transform = {rotation: {origin, axis:'xyz'[axes[0]], angle:rotation[axes[0]], rescale:true}};
                }
            }
            positions = positions.map(p => transformVertex(p,transform));
            faces.push({element:index, face:name, bone:ROLES.indexOf(role), positions, normal:faceNormal(positions),
                uvs: uvs.map(([u,v]) => [region[0]+u*region[2]/region[4], region[1]+v*region[3]/region[5]])});
        }
    }
    if (!faces.some(face => face.bone === 0)) fail('Assign at least one textured part to Gun before building.');
    return {settings, faces, artwork, dataY:row};
}
function skinUV([px,y,pz], [nx,ny,nz], bone, left, slim, outer) {
    const width = slim ? 3 : 4;
    const x = Math.min(width-.02, Math.max(.02, (px+2)/4*width)), z = Math.min(3.98,Math.max(.02,pz+2));
    y = Math.min(11.98,Math.max(.02,y));
    let u,v;
    if (ny < -.5) [u,v] = [4+x,4-z];
    else if (ny > .5) [u,v] = [4+width+x,4-z];
    else if (nx < -.5) [u,v] = [4-z,4+y];
    else if (nx > .5) [u,v] = [4+width+z,4+y];
    else if (nz < -.5) [u,v] = [4+x,4+y];
    else [u,v] = [8+2*width-x,4+y];
    const physicalLeft = (bone === 4) !== left;
    const origin = physicalLeft ? (outer ? [48,48] : [32,48]) : (outer ? [40,32] : [40,16]);
    return [256+origin[0]+u,16+origin[1]+v];
}
function armFaces() {
    const faces = [], lower = [-2,0,-2], upper = [2,12,2];
    for (const bone of [3,4]) for (const outer of [false,true]) for (const [name,corners] of Object.entries(FACE_CORNERS)) {
        const expansion = outer ? .25 : 0, bounds = [lower.map(n => n-expansion),upper.map(n => n+expansion)];
        const positions = corners.map(c => c.map((n,i) => bounds[n][i]));
        const base = corners.map(c => c.map((n,i) => [lower,upper][n][i])), normal = faceNormal(positions);
        const variants = [];
        for (const slim of [false,true]) for (const left of [false,true]) variants.push(base.map(p => skinUV(p,normal,bone,left,slim,outer)));
        faces.push({element:215+Math.floor(faces.length/6),face:name,bone,positions,normal,uvs:variants[0],skin_uvs:variants,skin_layer:outer?'outer':'base'});
    }
    return faces;
}
function muzzleFaces(anchor) {
    const rings = [[0,.11,.11,.5],[-1.2,3,.55,5.5],[-6,.18,.18,18.5],[-20,.006,.006,31.5]];
    const points = rings.map(([z,rx,ry]) => [[1,0],[0,1],[-1,0],[0,-1]].map(([x,y]) => [anchor[0]+x*rx,anchor[1]+y*ry,anchor[2]+z]));
    const faces = [];
    const add = (name,positions,samples) => faces.push({element:255,face:name,bone:7,positions,normal:faceNormal(positions),muzzle_anchor:anchor,
        muzzle_source_element:-1,uvs:samples.map(u => [336+u,17.5])});
    add('muzzle-root',points[0],Array(4).fill(rings[0][3]));
    for (let ring=0;ring<3;ring++) for (let side=0;side<4;side++) {
        const next=(side+1)%4;
        add(`muzzle-${ring}-${side}`,[points[ring][side],points[ring+1][side],points[ring+1][next],points[ring][next]],
            [rings[ring][3],rings[ring+1][3],rings[ring+1][3],rings[ring][3]]);
    }
    add('muzzle-tip',points[3].slice().reverse(),Array(4).fill(rings[3][3]));
    return faces;
}
function u16(value) {
    if (!Number.isInteger(value) || value < 0 || value > 65535) fail(`Value outside the unsigned 16-bit metadata range: ${value}`);
    return [value >> 8,value & 255];
}
function record(face, mode, x, y, profile, dataY) {
    const pos = n => u16(round(n*256+32768)), uv = n => u16(round(n*(dataY > 256 ? 32 : 128)));
    const pixels = [[17,143,81,255],[...u16(x),...u16(y)],[mode%4,dataY>256?2:1,(profile&1)|((profile>>1)<<2)|(mode>=4?2:0),255]];
    const [nx,ny,nz] = face.normal.map(n => round((n+1)*127.5));
    const coords = face.skin_uvs ? face.skin_uvs[mode%2] : face.uvs;
    face.positions.forEach(([vx,vy,vz],i) => {
        const [u,v] = coords[i];
        pixels.push([...pos(vx),...pos(vy)],[...pos(vz),nx,ny],[nz,face.bone,...uv(u)],[...uv(v),255,255]);
    });
    if (face.skin_uvs) pixels.push(...face.skin_uvs[2+mode%2].map(([u,v]) => [...uv(u),...uv(v)]));
    else if (face.muzzle_anchor) {
        const [x,y,z] = face.muzzle_anchor;
        pixels.push([...pos(x),...pos(y)],[...pos(z),0,255]);
    }
    return pixels;
}
// One fixed atlas covers every profile representable by the carrier protocol.
// Hands use only pose mode; empty-magazine state never changes hand geometry.
const HAND_TEXTURE = 'aechronis:item/hands';
function handRecord(profile, mode, face) {
    const index = (profile * 4 + mode % 4) * 24 + face;
    return [index % 32 * 32, 128 + Math.floor(index / 32)];
}
function bakeHandAtlas() {
    const atlas = image(1024,512), faces = armFaces();
    pixel(atlas,256,0,[83,75,73,255]);
    pixel(atlas,257,0,[0,0,0,255]);
    pixel(atlas,258,0,[0,0,0,255]);
    for (let profile=0;profile<16;profile++) for (let mode=0;mode<4;mode++) {
        faces.forEach((face,index) => {
            const [x,y] = handRecord(profile,mode,index);
            record(face,mode,x,y,profile,128).forEach((color,i) => pixel(atlas,x+i,y,color));
        });
    }
    return png.write(atlas);
}
function bakeAtlas(gun, source, curveData) {
    const {settings,artwork,dataY} = source, faces = [...source.faces,...armFaces(),...muzzleFaces(settings.muzzle)];
    const modes = ['hip-right','hip-left','aim-right','aim-left'];
    if (settings.hold_open ?? (settings.style === 'pistol')) modes.push('hip-right-empty','hip-left-empty');
    const height = Math.max(512,2**Math.ceil(Math.log2(dataY+Math.ceil(faces.length*modes.length/32))));
    if (height > 65536) fail('Too many faces for the shader atlas. Simplify the model.');
    const atlas = image(1024,height); paste(atlas,artwork,0,0);
    if (curveData) paste(atlas, require('./combat-curves.cjs').encode([curveData], {width:1024,maxHeight:320}), 0, 192);
    const stops = [[0,[255,250,206]],[6,[255,238,159]],[19,[255,184,57]],[31,[241,111,22]]];
    for (let x=0;x<32;x++) {
        const index = stops.findIndex((a,i) => i<3 && a[0]<=x && x<=stops[i+1][0]);
        const [begin,end] = [stops[index],stops[index+1]], mix = (x-begin[0])/(end[0]-begin[0]);
        const color = [...begin[1].map((a,i) => round(a+(end[1][i]-a)*mix)),255];
        for (let y=0;y<4;y++) pixel(atlas,336+x,16+y,color);
    }
    const models = {};
    modes.forEach((name,mode) => {
        const elements = faces.map((face,index) => {
            const hand = !!face.skin_uvs;
            const recordIndex = mode*faces.length+index;
            const [x,y] = hand ? handRecord(settings.id,mode,index-source.faces.length)
                : [recordIndex%32*32,dataY+Math.floor(recordIndex/32)];
            if (!hand) record(face,mode,x,y,settings.id,dataY).forEach((color,i) => pixel(atlas,x+i,y,color));
            const u=(x+.5)*16/1024,v=(y+.5)*16/(hand ? 512 : atlas.height);
            return {from:[7.9921875,8,7.9921875],to:[8.0078125,8,8.0078125],shade:false,
                faces:{up:{uv:[u,v,u,v],texture:hand?'#hands':'#0',tintindex:0}}};
        });
        models[name] = {credit:'Generated by the Aechronis Blockbench plugin',ambientocclusion:false,
            texture_size:[atlas.width,atlas.height],textures:{'0':`aechronis:item/${gun}-animation`,hands:HAND_TEXTURE,particle:`aechronis:item/${gun}`},elements};
    });
    return {models,atlas:png.write(atlas),faces,modes};
}
module.exports = {ROLES,RIG_FIELDS,FACE_CORNERS,clone,fail,vector,round,bitmap,png,image,paste,javaFace,faceNormal,settingsFor,projectSource,bakeAtlas,bakeHandAtlas,HAND_TEXTURE,handRecord};
