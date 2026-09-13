'use strict';

// Curve pages are ordinary opaque RGBA8 texels. All offsets are relative to
// the page origin; an atlas may place the page anywhere without rewriting it.
const {canonicalCurveData: canonical, curveDataKey: keyFor} = require('./aechronis_combat_animation.js');
const HEADER_PIXELS = 2, CHANNELS = 100, SEGMENT_PIXELS = 12;
const MAX_OFFSET = 0xffffff, MAX_SEGMENTS = 0x10000, MAX_TRACK_SEGMENTS = 192;
const fail = message => { throw new Error(`Combat curve data: ${message}`); };
const integer = (value, minimum, maximum, label) => {
    if (!Number.isInteger(value) || value < minimum || value > maximum) fail(`invalid ${label}.`);
};

function validate(data) {
    if (!data || data.version !== 1) fail('unsupported version.');
    integer(data.key, 1, MAX_OFFSET, 'content key');
    if (!Array.isArray(data.starts) || !Array.isArray(data.ends) || data.starts.length !== data.ends.length || data.starts.length > MAX_SEGMENTS) fail('invalid segment arrays.');
    for (const [index, point] of [...data.starts, ...data.ends].entries()) {
        if (!Array.isArray(point) || point.length !== 4 || point.some(value => typeof value !== 'number' || !Number.isFinite(value) || !Number.isFinite(Math.fround(value)))) fail(`invalid float32 endpoint ${index}.`);
        if (point[3] < 0 || point[3] > 1) fail('segment time is outside the clip.');
    }
    if (!Array.isArray(data.tracks) || data.tracks.length > CHANNELS) fail('invalid channel directory.');
    const slots = new Set();
    for (const track of data.tracks) {
        integer(track.clip, 0, 9, 'clip');
        integer(track.bone, 0, 4, 'bone');
        integer(track.channel, 0, 1, 'channel');
        integer(track.first, 0, MAX_SEGMENTS - 1, 'first segment');
        integer(track.count, 1, MAX_TRACK_SEGMENTS, 'segment count');
        const slot = (track.clip * 5 + track.bone) * 2 + track.channel;
        if (slots.has(slot)) fail('duplicate channel.');
        slots.add(slot);
        if (track.first + track.count > data.starts.length) fail('channel exceeds its segment arrays.');
        for (let index = track.first; index < track.first + track.count; index++) {
            const a = data.starts[index], b = data.ends[index];
            if (b[3] < a[3]) fail('segment ends before it starts.');
            if (index > track.first && a[3] <= data.starts[index - 1][3]) fail('channel boundaries must increase.');
            if (index > track.first && data.ends[index - 1][3] !== a[3]) fail('channel segments must be contiguous.');
        }
    }
    if (!data.pivots || typeof data.pivots !== 'object' || Array.isArray(data.pivots)) fail('missing pivots.');
    for (const [bone, pivot] of Object.entries(data.pivots)) {
        if (!/^[0-4]$/.test(bone) || !Array.isArray(pivot) || pivot.length !== 3 || pivot.some(value => typeof value !== 'number' || !Number.isFinite(value))) fail('invalid pivot.');
    }
    if (keyFor(data) !== data.key) fail('content key does not match the data.');
    return data;
}

function normalize(datasets) {
    if (!Array.isArray(datasets)) fail('expected a dataset array.');
    const entries = new Map();
    for (const data of datasets) {
        validate(data);
        const previous = entries.get(data.key);
        if (previous && canonical(previous) !== canonical(data)) fail(`24-bit content-key collision at ${data.key}; rebuild with a different curve-data key format.`);
        entries.set(data.key, data);
    }
    if (entries.size > 0xffff) fail('too many datasets.');
    return [...entries.values()].sort((a, b) => a.key - b.key);
}

function encode(datasets, {width = 1024, maxHeight = 0x10000} = {}) {
    integer(width, 1, 0x10000, 'page width');
    integer(maxHeight, 1, 0x10000, 'maximum height');
    const entries = normalize(datasets);
    const pixels = HEADER_PIXELS + entries.length * 2 + entries.reduce((total, data) => total + CHANNELS + data.starts.length * SEGMENT_PIXELS, 0);
    if (pixels - 1 > MAX_OFFSET) fail('page exceeds 24-bit offsets.');
    const height = Math.ceil(pixels / width);
    if (height > maxHeight) fail(`page needs ${height} rows, but only ${maxHeight} are available.`);
    const image = {width, height, data: Buffer.alloc(width * height * 4)};
    for (let pixel = 0; pixel < width * height; pixel++) image.data[pixel * 4 + 3] = 255;
    const pixel = (index, bytes) => image.data.set([...bytes, 255], index * 4);
    const u24 = value => [value >>> 16 & 255, value >>> 8 & 255, value & 255];
    const vector = (index, values) => {
        const bytes = Buffer.alloc(18);
        values.forEach((value, axis) => bytes.writeFloatLE(value, axis * 4));
        for (let offset = 0; offset < 6; offset++) pixel(index + offset, bytes.subarray(offset * 3, offset * 3 + 3));
    };
    pixel(0, [65, 67, 86]);
    pixel(1, [1, entries.length >>> 8, entries.length & 255]);
    let block = HEADER_PIXELS + entries.length * 2;
    for (const [index, data] of entries.entries()) {
        pixel(HEADER_PIXELS + index * 2, u24(data.key));
        pixel(HEADER_PIXELS + index * 2 + 1, u24(block));
        for (const track of data.tracks) {
            const slot = (track.clip * 5 + track.bone) * 2 + track.channel;
            pixel(block + slot, [track.first >>> 8, track.first & 255, track.count]);
        }
        for (let segment = 0; segment < data.starts.length; segment++) {
            vector(block + CHANNELS + segment * SEGMENT_PIXELS, data.starts[segment]);
            vector(block + CHANNELS + segment * SEGMENT_PIXELS + 6, data.ends[segment]);
        }
        block += CHANNELS + data.starts.length * SEGMENT_PIXELS;
    }
    return image;
}

module.exports = {encode, normalize, validate, canonical, keyFor, HEADER_PIXELS, CHANNELS, SEGMENT_PIXELS, MAX_TRACK_SEGMENTS};
