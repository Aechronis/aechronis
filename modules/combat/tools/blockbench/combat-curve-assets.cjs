'use strict';

const fs = require('node:fs');
const path = require('node:path');
const mesh = require('./combat-mesh.cjs');
const curves = require('./combat-curves.cjs');
const profiles = require('./combat-profiles.cjs');

const PAGE_Y = 192;
const curveFile = (root, pack, gun) => path.join(root, 'modules/iterations', pack,
    'animations', gun + '.json');

// Each iteration owns its published curves. Never read another iteration's
// editable projects or snapshots while rebuilding these atlases.
function collect(root, pack, pending = new Map()) {
    const entries = new Map();
    const directory = path.dirname(curveFile(root, pack, 'placeholder'));
    if (fs.existsSync(directory)) for (const file of fs.readdirSync(directory, {withFileTypes: true})) {
        if (file.isFile() && file.name.endsWith('.json')) {
            const destination = path.join(directory, file.name);
            entries.set(destination, fs.readFileSync(destination));
        }
    }
    for (const gun of Object.keys(profiles.readProfiles(root, pack))) {
        const destination = curveFile(root, pack, gun);
        if (entries.has(destination) || pending.has(destination)) continue;
        throw new Error(`Build the saved project for ${pack}/${gun} before exporting texture curves.`);
    }
    for (const [file, content] of pending) {
        if (path.dirname(file) !== directory || !file.endsWith('.json')) continue;
        if (content === null) entries.delete(file); else entries.set(file, content);
    }
    return curves.normalize([...entries.values()].map(value => JSON.parse(value)));
}

function page(datasets) {
    return curves.encode(datasets, {width: 1024, maxHeight: 512 - PAGE_Y});
}

function iterationAssets(root, pack, pending = new Map()) {
    const data = page(collect(root, pack, pending));
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

module.exports = {PAGE_Y, curveFile, collect, page, iterationAssets};
