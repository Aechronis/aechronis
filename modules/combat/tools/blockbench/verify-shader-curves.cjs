'use strict';

// Decode published RGBA bytes independently and compare against the editor
// sampler. Float32 key times model the shader's boundary precision explicitly.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const plugin = require('./aechronis_combat_animation.js');
const codec = require('./combat-curves.cjs');
const png = require('pngjs').PNG.sync;
const root = path.resolve(__dirname, '../../../..');
let comparisons = 0, largestError = 0, largestFloatError = 0, bytes = 0;
const datasets = [];

function decode(page) {
    // Place the page at an unrelated atlas offset/stride. Stored block offsets
    // must still resolve relative to the page rather than the atlas or texture.
    const origin = [13, 7], width = page.width + 29;
    const atlas = Buffer.alloc(width * (page.height + 11) * 4);
    for (let row = 0; row < page.height; row++) page.data.copy(atlas,
        ((row + origin[1]) * width + origin[0]) * 4, row * page.width * 4, (row + 1) * page.width * 4);
    const pixel = index => {
        const offset = ((origin[1] + Math.floor(index / page.width)) * width + origin[0] + index % page.width) * 4;
        const result = [...atlas.subarray(offset, offset + 4)];
        assert.equal(result[3], 255);
        return result;
    };
    const uint24 = value => value[0] * 65536 + value[1] * 256 + value[2];
    const vector = index => {
        const bytes = Uint8Array.from(Array.from({length: 6}, (_, offset) => pixel(index + offset).slice(0, 3)).flat());
        assert.equal(bytes[16], 0); assert.equal(bytes[17], 0);
        const view = new DataView(bytes.buffer);
        return Array.from({length: 4}, (_, axis) => view.getFloat32(axis * 4, true));
    };
    assert.deepEqual(pixel(0), [65, 67, 86, 255]);
    const header = pixel(1);
    assert.equal(header[0], 1);
    const count = header[1] * 256 + header[2], directory = new Map();
    let previous = 0;
    for (let entry = 0; entry < count; entry++) {
        const key = uint24(pixel(2 + entry * 2)), block = uint24(pixel(3 + entry * 2));
        assert(key > previous); previous = key;
        const channels = Array.from({length: 100}, (_, slot) => {
            const record = pixel(block + slot), first = record[0] * 256 + record[1];
            return Array.from({length: record[2]}, (_, segment) => ({
                a: vector(block + 100 + (first + segment) * 12),
                b: vector(block + 106 + (first + segment) * 12)
            }));
        });
        directory.set(key, channels);
    }
    return (key, clip, bone, channel, time) => {
        const channels = directory.get(key);
        if (!channels || clip < 0 || clip > 9 || bone < 0 || bone > 4 || channel < 0 || channel > 1) return [0, 0, 0];
        const segments = channels[(clip * 5 + bone) * 2 + channel];
        if (!segments.length) return [0, 0, 0];
        const {a, b} = segments.findLast(segment => segment.a[3] <= time) ?? segments[0];
        const fraction = b[3] === a[3] ? 0 : Math.min(1, Math.max(0, (time - a[3]) / (b[3] - a[3])));
        return a.slice(0, 3).map((value, axis) => value + (b[axis] - value) * fraction);
    };
}

function check(project) {
    const data = plugin.extractCurveData(project), shader = plugin.compileShader(project);
    datasets.push(data); bytes += Buffer.byteLength(shader);
    assert(!/const vec4|vec3 authored =|for \(/.test(shader), 'Curve volume must never become shader code or constant arrays.');
    assert(Buffer.byteLength(shader) < 2048, 'Per-model shader metadata must stay small regardless of key count.');
    assert(shader.includes(`return ${data.key}u;`));
    assert(!shader.includes('aechronis_authored_track_'));
    assert.equal(data.key, codec.keyFor({...data, key: 0}));
    const pages = [1024, 256].map(width => codec.encode([data], {width}));
    const samplers = pages.map(decode);
    for (const [clip, tracks] of Object.entries(project.clips)) {
        for (const track of tracks) {
            const reference = track.reference || [];
            const boundaries = [...new Set([...track.keys, ...reference].map(key => key.time))].sort((a, b) => a - b);
            const times = new Set([-1, 0, 1, 2]);
            for (const [index, time] of boundaries.entries()) {
                times.add(time); times.add(time - 1e-7); times.add(time + 1e-7);
                if (index) for (const fraction of [.17, .5, .83]) times.add(boundaries[index - 1] + (time - boundaries[index - 1]) * fraction);
            }
            const quantized = keys => keys.map(key => ({...key, time: Math.fround(key.time)}));
            const floatKeys = quantized(track.keys), floatReference = quantized(reference);
            const route = data.tracks.find(value => value.clip === plugin.CLIPS[clip].id && value.bone === track.bone && value.channel === track.channel);
            for (const time of times) {
                const expected = plugin.sampleTrack(track.keys, time).map((value, axis) => value - plugin.sampleTrack(reference, time)[axis]);
                let actual = [0, 0, 0];
                if (route) {
                    const indices = Array.from({length: route.count}, (_, index) => route.first + index);
                    const index = indices.findLast(index => data.starts[index][3] <= time) ?? indices[0];
                    const a = data.starts[index], b = data.ends[index];
                    const amount = b[3] === a[3] ? 0 : Math.max(0, Math.min(1, (time - a[3]) / (b[3] - a[3])));
                    actual = a.slice(0, 3).map((value, axis) => value + (b[axis] - value) * amount);
                }
                const error = Math.max(...actual.map((value, axis) => Math.abs(value - expected[axis])));
                largestError = Math.max(largestError, error); comparisons++;
                assert(error < 1e-7, `${project.profile}/${clip}/${track.bone}/${track.channel} at ${time}: ${actual} != ${expected}`);
                // A step belongs to its float32 boundary after upload, even if
                // rounding moves that boundary slightly left of the source key.
                const floatTime = Math.fround(time);
                const floatExpected = plugin.sampleTrack(floatKeys, floatTime).map((value, axis) => value - plugin.sampleTrack(floatReference, floatTime)[axis]);
                for (const sample of samplers) {
                    const value = sample(data.key, plugin.CLIPS[clip].id, track.bone, track.channel, floatTime);
                    const error = Math.max(...value.map((value, axis) => Math.abs(value - floatExpected[axis])));
                    largestFloatError = Math.max(largestFloatError, error);
                    assert(error < .0005, `${project.profile}/${clip}/${track.bone}/${track.channel} float32 at ${floatTime}: ${value} != ${floatExpected}`);
                }
            }
        }
    }
    for (const sample of samplers) {
        assert.deepEqual(sample(data.key, 99, 99, 99, .5), [0, 0, 0]);
        assert.deepEqual(sample(0, 2, 1, 0, .5), [0, 0, 0]);
    }
}

const key = (time, value, interpolation = 'linear') => ({time, value, interpolation});
const v = number => [number, -number * .7, number * 2.1];
const cases = [
    [[], []],
    [[key(.4, v(3))], []],
    [[key(0, v(3)), key(1, v(3))], [key(.2, v(1)), key(.8, v(1))]],
    [[], [key(.1, v(1), 'step'), key(1 / 3, v(9), 'step'), key(.9, v(4))]],
    [[key(.1, v(2), 'step'), key(1 / 3, v(7)), key(.8, v(-2), 'step'), key(.95, v(4))],
        [key(0, v(1)), key(.25, v(6), 'step'), key(.6, v(-8)), key(.95, v(3), 'step'), key(1, v(0))]],
    [[key(0, v(0)), key(.5, v(4)), key(.50000000000001, v(4)), key(1, v(0))],
        [key(0, v(0)), key(.500000000000005, v(4)), key(1, v(0))]],
    [[key(.2, v(3), 'step'), key(.7, v(-3), 'step'), key(1, v(7))],
        [key(.2, v(3), 'step'), key(.7, v(-3), 'step'), key(1, v(2))]]
];
let randomState = 17;
const random = () => ((randomState = (Math.imul(randomState, 1664525) + 1013904223) >>> 0) / 0x100000000);
for (let index = 0; index < 20; index++) {
    const curve = () => Array.from({length: 96}, (_, keyIndex) => key((keyIndex + random() * .5) / 96,
        [random() * 20 - 10, random() * 20 - 10, random() * 20 - 10], random() < .3 ? 'step' : 'linear'));
    cases.push([curve(), curve()]);
}
for (const [index, [keys, reference]] of cases.entries()) check({profile: `synthetic${index}`, reload_ticks: 60, pivots: {},
    clips: {reload: [{bone: 1, channel: 0, keys, reference}], reload_aim: [{bone: 1, channel: 0, keys, reference}]}});

let models = 0;
const iterations = path.join(root, 'modules/iterations');
for (const entry of fs.readdirSync(iterations, {withFileTypes: true})) {
    if (!entry.isDirectory() || entry.name.startsWith('.')) continue;
    const directory = path.join(iterations, entry.name, 'models');
    if (!fs.existsSync(directory)) continue;
    for (const name of fs.readdirSync(directory).filter(name => name.endsWith('.bbmodel'))) {
        const model = JSON.parse(fs.readFileSync(path.join(directory, name), 'utf8'));
        if (model.meta?.model_format !== 'aechronis_combat_animation') continue;
        check(plugin.extractProject(model)); models++;
    }
}
assert(models > 0, 'No combat models found.');
// A combined page exercises dictionary sorting, repeated content, PNG byte
// preservation, and explicit failure for invalid keys, collisions and capacity.
const combined = codec.encode([...datasets].reverse().concat(datasets[0]));
const restored = png.read(png.write(combined));
assert.deepEqual(restored.data, combined.data);
decode(restored);
assert.equal(codec.normalize([datasets[0], datasets[0]]).length, 1);
assert.throws(() => codec.encode(datasets, {width: 256, maxHeight: 1}), /only 1 are available/);
assert.throws(() => codec.encode(datasets, {width: 0}), /page width/);
assert.throws(() => codec.encode([{...datasets[0], key: 0}]), /content key/);
const collision = value => {
    const data = {version: 1, starts: [], ends: [], tracks: [], pivots: {1: [value, 0, 0]}};
    return {...data, key: codec.keyFor(data)};
};
assert.throws(() => codec.encode([collision(246.15), collision(409.41)]), /collision/);
console.log(`${models} combat models and ${cases.length} synthetic curve pairs: ${comparisons} source samples, max error ${largestError}; both texture widths max float32 error ${largestFloatError}; ${bytes} generated shader bytes.`);
