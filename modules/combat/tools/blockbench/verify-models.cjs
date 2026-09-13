'use strict';

// Read-only source/export regression check. Run after Build combat assets.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const plugin = require('./aechronis_combat_animation.js');
const mesh = require('./combat-mesh.cjs');
const project = require('./combat-project.cjs');
const profiles = require('./combat-profiles.cjs');
const root = path.resolve(__dirname, '../../../..');
const read = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const near = (actual, expected, label, tolerance = 1e-7) => assert(
    actual.length === expected.length && actual.every((n, i) => Math.abs(n - expected[i]) <= tolerance),
    `${label}: ${JSON.stringify(actual)} != ${JSON.stringify(expected)}`);

// Measured September 2026 audit landmarks, not the model inventory. Unknown
// future models still receive all rig, timing, serialization and export checks.
const sights = {
    glock17: {point: [8.01875, 10.5102], front: 73, rear: [65, 69]},
    m9: {point: [8.225, 10.26807], front: 113, rear: [104, 109]},
    mp5: {point: [8, 9.26], front: 144, rear: [80, 81]},
    vz61: {point: [8.1951, 9.47], front: 57, rear: [18, 31]},
    ak12: {point: [7.98131, 8.9414], front: 108, rear: [78, 81, 82, 83]},
    ak74: {point: [7.82912, 10.01477], front: 25, rear: [41, 42, 43]},
    m4a1: {point: [7.93251, 9.637315], front: 168, rear: [161, 162, 163, 164, 165, 166, 167, 214]},
    g3: {point: [8, 7.28014], front: 109, rear: [55, 56, 57, 58, 59, 106]},
    'qbz-95': {point: [7.94, 11.9], front: 17, rear: [31, 32, 35, 36, 38, 39, 41, 57]},
    ak47: {point: [7.99724, 8.66608], front: 67, rear: [77, 78, 79, 80, 81, 82, 83, 84]},
    mg3: {point: [8.796875, 8.47932], front: 7, rear: [11, 12]},
    at4: {point: [6.894115, 7.86148], front: 142, rear: [133, 134, 136, 137]},
    awp: {point: [8, 8.825], lens: 643, rear: Array.from({length: 32}, (_, i) => 472 + i)}
};

function* files(directory, extension) {
    if (!fs.existsSync(directory)) return;
    for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
        if (entry.name.startsWith('.')) continue;
        const file = path.join(directory, entry.name);
        if (entry.isDirectory()) yield* files(file, extension);
        else if (entry.isFile() && entry.name.endsWith(extension)) yield file;
    }
}
function assertAsset(file, expected) {
    assert(!file.endsWith('.bbmodel'), 'Build must never save an editable project');
    assert(fs.existsSync(file), `Missing exported asset: ${path.relative(root, file)}`);
    assert(Buffer.isBuffer(expected) && fs.readFileSync(file).equals(expected),
        `Stale exported asset: ${path.relative(root, file)}; rebuild its saved model`);
}
function kotlinGuns(iteration) {
    const result = [];
    for (const file of files(path.join(iteration, 'src'), 'Guns.kt')) {
        const text = fs.readFileSync(file, 'utf8');
        const starts = [...text.matchAll(/\bval\s+\w+\s*=\s*Gun\s*\(/g)];
        starts.forEach((match, i) => {
            const block = text.slice(match.index, starts[i + 1]?.index ?? text.length);
            const name = block.match(/\bname\s*=\s*"([^"]+)"/)?.[1];
            const value = key => block.match(new RegExp(`\\b${key}\\s*=\\s*(\\d+)`))?.[1];
            const id = value('animatedViewModelProfile');
            if (id !== undefined) result.push({name, id: Number(id), reload: Number(value('reloadTime')),
                fire: value('fireAnimationTicks') === undefined ? defaultFireTicks : Number(value('fireAnimationTicks')),
                item: block.match(/\bitemModel\s*=\s*"([^"]+)"/)?.[1]});
        });
    }
    return result;
}
function bounds(source, indices) {
    const points = source.faces.filter(f => indices.some(i => f.face.startsWith(`e${i}_`))).flatMap(f => f.positions);
    assert(points.length, `Missing audited geometry: ${indices}`);
    return [0, 1, 2].map(axis => [Math.min(...points.map(p => p[axis])), Math.max(...points.map(p => p[axis]))]);
}
// Orthographic ray through the camera's optical axis; use actual textured
// triangles so invisible helper texels do not falsely obstruct an aperture.
function sightHit(source, x, y) {
    const cameraZ = 8 - source.settings.hipRoot[2] * 16;
    let nearest;
    for (const face of source.faces) for (const ids of [[0, 1, 2], [0, 2, 3]]) {
        const [a, b, c] = ids.map(i => face.positions[i]);
        const divisor = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
        if (Math.abs(divisor) < 1e-12) continue;
        const l0 = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / divisor;
        const l1 = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / divisor;
        const weights = [l0, l1, 1 - l0 - l1];
        if (weights.some(n => n < -1e-9)) continue;
        const z = weights.reduce((sum, n, i) => sum + n * face.positions[ids[i]][2], 0);
        if (z >= cameraZ || nearest && nearest.z >= z) continue;
        const uv = [0, 1].map(axis => Math.floor(weights.reduce((sum, n, i) => sum + n * face.uvs[ids[i]][axis], 0)));
        const art = source.artwork;
        const alpha = art.data[(Math.max(0, Math.min(art.height - 1, uv[1])) * art.width + Math.max(0, Math.min(art.width - 1, uv[0]))) * 4 + 3];
        if (alpha > 127) nearest = {z, element: Number(face.face.match(/^e(\d+)_/)?.[1])};
    }
    return nearest;
}
function checkSight(gun, source, model, iteration) {
    const audit = sights[gun];
    if (!audit) return;
    near(source.settings.sight, audit.point, `${gun} saved sight`);
    const b = bounds(source, [audit.front ?? audit.lens]);
    near([(b[0][0] + b[0][1]) / 2, audit.lens === undefined ? b[1][1] : (b[1][0] + b[1][1]) / 2],
        audit.point, `${gun} physical sight`, 1e-5);
    if (audit.lens === undefined) {
        const above = sightHit(source, audit.point[0], audit.point[1] + 1e-5);
        assert(!above, `${gun} optical axis is obstructed by element ${above?.element}`);
        assert.equal(sightHit(source, audit.point[0], audit.point[1] - 1e-5)?.element, audit.front,
            `${gun} front post must be the visible surface immediately below the zero ray`);
    } else {
        const rear = bounds(source, audit.rear);
        near(rear.slice(0, 2).map(([a, b]) => (a + b) / 2), audit.point, `${gun} ocular centre`, .002);
    }
    const original = model.aechronis_combat.java_source.model;
    for (const suffix of ['', '-aiming', '-empty']) {
        const file = path.join(iteration, `resource-pack/assets/aechronis/models/item/${gun}${suffix}.json`);
        const published = read(file);
        if (!published.elements) continue;
        for (const index of [audit.front ?? audit.lens, ...audit.rear]) {
            const expected = original.elements[index];
            const slideOffset = suffix === '-empty' && source.settings.style === 'pistol' ? 2 : 0;
            assert(published.elements.some(element => JSON.stringify(element.faces) === JSON.stringify(expected.faces)
                && ['from', 'to'].every(key => element[key].every((v, axis) => Math.abs(v - expected[key][axis] - (axis === 2 ? slideOffset : 0)) < 1e-7))
                && (!expected.rotation || element.rotation?.axis === expected.rotation.axis && element.rotation?.angle === expected.rotation.angle
                    && element.rotation.origin.every((v, axis) => Math.abs(v - expected.rotation.origin[axis] - (axis === 2 ? slideOffset : 0)) < 1e-7))),
            `${gun}${suffix}: sight element ${index} differs from editable source`);
        }
    }
}
function checkEmptyReload(gun, iteration, source, model) {
    const item = read(path.join(iteration, `resource-pack/assets/aechronis/items/${gun}-reloading.json`));
    for (const hand of ['right', 'left']) {
        const model = item.model.cases.find(entry => entry.when === `firstperson_${hand}hand`)?.model;
        assert.equal(model?.type, 'minecraft:range_dispatch', `${gun}: empty reload needs damage dispatch`);
        assert.equal(model.property, 'minecraft:damage');
        assert.equal(model.normalize, false);
        assert.equal(model.fallback.model, `aechronis:item/${gun}-animation-hip-${hand}`);
        assert.deepEqual(model.entries.map(entry => [entry.threshold, entry.model.model]),
            [[99, `aechronis:item/${gun}-animation-hip-${hand}-empty`]]);
        for (const suffix of ['-empty', '-empty-equip']) {
            const emptyItem = read(path.join(iteration, `resource-pack/assets/aechronis/items/${gun}${suffix}.json`));
            assert.equal(emptyItem.model.cases.find(entry => entry.when === `firstperson_${hand}hand`)?.model.model,
                `aechronis:item/${gun}-animation-hip-${hand}-empty`, `${gun}${suffix}: hold-open carrier`);
        }
    }
    const empty = read(path.join(iteration, `resource-pack/assets/aechronis/models/item/${gun}-empty.json`));
    const moving = new Set(source.faces.filter(face => face.bone === 2).map(face => Number(face.face.match(/^e(\d+)_/)?.[1])));
    const travel = source.settings.style === 'pistol' ? 2 : 1.45;
    for (const index of moving) {
        if (!Number.isInteger(index)) continue;
        const expected = model.aechronis_combat.java_source.model.elements[index];
        assert(empty.elements.some(element => JSON.stringify(element.faces) === JSON.stringify(expected.faces)
            && ['from', 'to'].every(key => element[key].every((v, axis) => Math.abs(v - expected[key][axis] - (axis === 2 ? travel : 0)) < 1e-7))
            && (!expected.rotation || element.rotation?.axis === expected.rotation.axis && element.rotation?.angle === expected.rotation.angle
                && element.rotation.origin.every((v, axis) => Math.abs(v - expected.rotation.origin[axis] - (axis === 2 ? travel : 0)) < 1e-7))),
        `${gun}-empty: moving part ${index} must match its runtime hold-open travel`);
    }
}

const animationKotlin = fs.readFileSync(path.join(root, 'modules/combat/src/main/kotlin/net/aechronis/combat/utils/GunAnimation.kt'), 'utf8');
const defaultFireTicks = Number(animationKotlin.match(/const val GUN_FIRE_ANIMATION_TICKS\s*=\s*(\d+)/)?.[1]);
assert(Number.isInteger(defaultFireTicks));
let modelsChecked = 0, assetsChecked = 0;
for (const pack of project.packs(root)) {
    const iteration = path.join(root, 'modules/iterations', pack);
    const modelFiles = [...files(path.join(iteration, 'models'), '.bbmodel')].sort();
    if (!modelFiles.length) continue;
    const catalog = profiles.readProfiles(root, pack), expectedCatalog = structuredClone(catalog), guns = kotlinGuns(iteration), names = new Set();
    for (const file of modelFiles) {
        const before = fs.readFileSync(file), model = JSON.parse(before), snapshot = JSON.stringify(model), config = model.aechronis_combat;
        const gun = config.profile, published = catalog[gun];
        assert(published, `${pack}/${gun}: no published profile`);
        assert(!names.has(gun), `${pack}: duplicate project for ${gun}`); names.add(gun);
        const extracted = plugin.extractProject(model);
        assert.deepEqual(extracted.warnings, [], `${gun}: animation continuity warnings`);
        assert.deepEqual(Object.keys(extracted.clips).sort(), Object.keys(plugin.CLIPS).sort(), `${gun}: missing clips`);
        const source = mesh.projectSource(model, {...config.profile_settings, id: published.id, pack, reload_ticks: config.reload_ticks});
        const saved = plugin.profileFor(config);
        for (const field of ['magazine', 'dominant_hand', 'support_hand', 'sight', 'hipRoot', 'muzzle']) {
            near(source.settings[field], saved[field], `${gun} rig ${field}`);
            const value = field === 'muzzle' ? source.settings[field].map(v => mesh.round(v * 256) / 256) : source.settings[field];
            near(published[field], value, `${gun} published ${field}`, 1e-7);
        }
        for (const field of ['reload_ticks', 'style', 'scoped']) assert.equal(published[field], source.settings[field], `${gun} published ${field}`);
        assert.equal(published.hold_open, source.settings.hold_open ?? (source.settings.style === 'pistol'), `${gun} published hold-open`);
        assert.equal(published.fire_ticks, source.settings.fire_ticks ?? defaultFireTicks, `${gun} published fire duration`);
        const consumers = guns.filter(g => g.id === published.id);
        assert(consumers.some(g => g.name === gun), `${gun}: missing Kotlin gun definition`);
        for (const consumer of consumers) {
            // The mounted MG3 deliberately uses a longer server-owned reload.
            if (!(consumer.name === 'plane-mg3' && consumer.item === 'aechronis:mg3')) assert.equal(consumer.reload, config.reload_ticks * 50, `${consumer.name} Kotlin reload duration`);
            assert.equal(consumer.fire, source.settings.fire_ticks ?? defaultFireTicks, `${consumer.name} Kotlin fire duration`);
        }
        assertAsset(path.join(profiles.includeDirectory(root, pack), `gun_animation_tracks_${gun.replaceAll('-', '_')}.glsl`), Buffer.from(plugin.compileShader(extracted)));
        const {assets} = project.buildAssets(root, model, source, false, extracted);
        for (const [destination, content] of assets) {
            const allowed = ['resource-pack', 'animations'].some(directory => {
                const relative = path.relative(path.join(iteration, directory), destination);
                return relative && !relative.startsWith('..') && !path.isAbsolute(relative);
            });
            assert(allowed, `${gun}: build writes outside its iteration's exported assets: ${destination}`);
            assertAsset(destination, content); assetsChecked++;
        }
        assert.equal(JSON.stringify(model), snapshot, `${gun}: build mutated its input model`);
        assert(fs.readFileSync(file).equals(before), `${gun}: build saved the editable project`);
        checkSight(gun, source, model, iteration);
        if (source.settings.hold_open ?? (source.settings.style === 'pistol')) checkEmptyReload(gun, iteration, source, model);
        expectedCatalog[gun] = source.settings; modelsChecked++;
    }
    assert.deepEqual([...names].sort(), Object.keys(catalog).sort(), `${pack}: published profiles need saved source models`);
    for (const [file, content] of profiles.iterationAssets(root, pack, expectedCatalog)) { assertAsset(file, content); assetsChecked++; }
}
assert(modelsChecked, 'No saved combat models discovered');
console.log(`Verified ${modelsChecked} saved models and ${assetsChecked} generated assets: timing, rig, sights, empty reloads, and source/export parity. No files written.`);
