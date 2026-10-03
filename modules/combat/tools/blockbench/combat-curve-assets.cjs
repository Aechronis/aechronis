'use strict';

const fs = require('node:fs');
const path = require('node:path');
const mesh = require('./combat-mesh.cjs');
const curves = require('./combat-curves.cjs');
const profiles = require('./combat-profiles.cjs');
const plugin = require('./aechronis_combat_animation.js');

const PAGE_Y = 192;

function* modelFiles(directory) {
    if (!fs.existsSync(directory)) return;
    for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
        if (entry.name.startsWith('.')) continue;
        const file = path.join(directory, entry.name);
        if (entry.isDirectory()) yield* modelFiles(file);
        else if (entry.isFile() && entry.name.endsWith('.bbmodel')) yield file;
    }
}

// Rebuild from this iteration's saved projects, using the in-memory curves for
// the gun currently being exported. Published content keys keep profile renames
// independent of project filenames and prevent other draft edits from silently
// replacing curves still referenced by the exported shaders.
function collect(root, pack, pending = new Map(), guns = Object.keys(profiles.readProfiles(root, pack))) {
    const required = new Map(), entries = [...pending.values()];
    for (const gun of guns) {
        if (pending.has(gun)) continue;
        const name = gun.replaceAll('-', '_');
        const file = path.join(profiles.includeDirectory(root, pack), `gun_animation_tracks_${name}.glsl`);
        const shader = fs.readFileSync(file, 'utf8');
        const key = Number(shader.match(new RegExp(`uint aechronis_curve_key_${name}\\(\\)\\s*\\{\\s*return (\\d+)u;\\s*\\}`))?.[1]);
        if (!Number.isInteger(key) || key < 1 || key > 0xffffff) throw new Error(`Invalid published animation key: ${file}`);
        required.set(gun, key);
    }
    const directory = path.join(root, 'modules/iterations', pack, 'models');
    const wanted = new Set(required.values()), errors = [];
    if (wanted.size) for (const file of modelFiles(directory)) {
        try {
            const model = JSON.parse(fs.readFileSync(file, 'utf8'));
            if (model.meta?.model_format !== 'aechronis_combat_animation') continue;
            const data = plugin.extractCurveData(plugin.extractProject(model));
            if (wanted.has(data.key)) entries.push(data);
        } catch (error) {
            // Unpublished drafts need not be valid to build another gun. Report
            // their errors if they leave a published profile without its source.
            errors.push(`${path.relative(root, file)}: ${error.message}`);
        }
    }
    const datasets = curves.normalize(entries), available = new Set(datasets.map(data => data.key));
    for (const [gun, key] of required) {
        if (available.has(key)) continue;
        throw new Error(`No saved .bbmodel matches the published animation curves for ${pack}/${gun}. ` +
            `Save the exported project under ${directory}, or rebuild that gun from its edited project first.` +
            (errors.length ? '\n' + errors.join('\n') : ''));
    }
    return datasets;
}

function page(datasets) {
    // The former JSON snapshots converted -0 to 0. Preserve those float32
    // bytes in shared textures when deriving curves directly from projects.
    return curves.encode(JSON.parse(JSON.stringify(datasets)), {width: 1024, maxHeight: 512 - PAGE_Y});
}

function iterationAssets(root, pack, pending = new Map(), guns) {
    const data = page(collect(root, pack, pending, guns));
    const resourcePack = path.join(root, 'modules/iterations', pack, 'resource-pack');
    const hands = mesh.png.read(mesh.bakeHandAtlas());
    mesh.paste(hands, data, 0, PAGE_Y);
    const flash = mesh.image(1024, 512);
    const artwork = mesh.png.read(fs.readFileSync(path.join(__dirname, 'assets/flash.png')));
    if (artwork.width !== 32 || artwork.height !== 32) throw new Error('Flash artwork must remain 32 by 32 pixels.');
    mesh.paste(flash, artwork, 0, 0);
    // Version 2 marks the expanded data sprite; ordinary Flash particles sample
    // only the original artwork, while owner-only tracers can read curve data.
    flash.data.set([26, 2, 2, 1], 4);
    mesh.paste(flash, data, 0, PAGE_Y);
    return new Map([
        [path.join(resourcePack, 'assets/aechronis/textures/item/hands.png'), mesh.png.write(hands)],
        [path.join(resourcePack, 'assets/minecraft/textures/particle/flash.png'), mesh.png.write(flash)]
    ]);
}

module.exports = {PAGE_Y, collect, page, iterationAssets};
