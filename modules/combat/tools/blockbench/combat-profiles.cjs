'use strict';

const fs=require('node:fs');
const path=require('node:path');
const mesh=require('./combat-mesh.cjs');

// Runtime values come from the generated GLSL, not a second JSON catalog.
// Header records only identify the gun/project and its authoring duration.
function readProfiles(root,pack) {
    const file=path.join(includeDirectory(root,pack),'gun_profiles.glsl');
    if (!fs.existsSync(file)) return {};
    const text=fs.readFileSync(file,'utf8'), result={};
    const fail=()=>mesh.fail('Invalid combat profile shader: '+file);
    const records=[...text.matchAll(/^\/\/ profile (\d+) ([a-z][a-z0-9-]*) (\d+)$/gm)];
    function body(type,name) {
        const match=text.match(new RegExp(type+' '+name+'\\(int profile\\) \\{([\\s\\S]*?)\\}'));
        if (!match) fail();
        return match[1].trim();
    }
    function predicate(name) {
        const value=body('bool','aechronis_'+name);
        if (value==='return false;') return new Set();
        if (!/^return profile == \d+(?: \|\| profile == \d+)*;$/.test(value)) fail();
        return new Set([...value.matchAll(/profile == (\d+)/g)].map(m=>Number(m[1])));
    }
    const predicates=Object.fromEntries(['valid_profile','pistol','belt','launcher','scoped'].map(name=>[name,predicate(name)]));
    // Older exports only lock pistol slides open; the optional predicate adds
    // rifles/SMGs with a last-round bolt catch without changing their style.
    predicates.locks_open=/bool aechronis_locks_open\(int profile\)/.test(text)
        ? predicate('locks_open') : new Set(predicates.pistol);
    const vectors={};
    for (const field of ['hip','sight','magazine','dominant','support','muzzle']) {
        const value=body('vec3','aechronis_profile_'+field), table=new Map();
        const remainder=value.replace(/if \(profile == (\d+)\) return vec3\(([^)]+)\);/g,(_,id,xyz)=>{
            const numbers=xyz.split(',').map(n=>Number(n.trim()));
            if (numbers.length!==3 || numbers.some(n=>!Number.isFinite(n)) || table.has(Number(id))) fail();
            table.set(Number(id),numbers);return '';
        }).trim();
        if (remainder!=='return vec3(0.0);') fail();
        vectors[field]=table;
    }
    const ids=new Set();
    const fireBody=text.match(/float aechronis_profile_fire_ticks\(int profile\) \{([\s\S]*?)\}/);
    const fireTicks=new Map();
    if (fireBody) {
        const remainder=fireBody[1].replace(/if \(profile == (\d+)\) return (\d+)\.0;/g,(_,id,ticks)=>{
            if (fireTicks.has(Number(id)) || Number(ticks)<1 || Number(ticks)>255) fail();
            fireTicks.set(Number(id),Number(ticks));return '';
        }).trim();
        if (remainder!=='return 2.0;') fail();
    }
    for (const [,rawId,gun,ticks] of records) {
        const id=Number(rawId),reload_ticks=Number(ticks);
        if (id>15 || ids.has(id) || result[gun] || reload_ticks<1 || reload_ticks>255) fail();
        ids.add(id);
        const point=field=>vectors[field].get(id) || fail();
        const style=['pistol','belt','launcher'].filter(kind=>predicates[kind].has(id));
        if (style.length>1) fail();
        const profile={id,pack,name:gun.toUpperCase(),reload_ticks,fire_ticks:fireTicks.get(id) ?? 2,style:style[0] || 'rifle',
            scoped:predicates.scoped.has(id),hold_open:predicates.locks_open.has(id),
            hipRoot:point('hip'),sight:point('sight').slice(0,2),magazine:point('magazine'),dominant_hand:point('dominant'),support_hand:point('support'),muzzle:point('muzzle')};
        result[gun]=profile;
    }
    if (predicates.valid_profile.size!==ids.size || [...predicates.valid_profile].some(id=>!ids.has(id))) fail();
    for (const set of Object.values(predicates)) if ([...set].some(id=>!ids.has(id))) fail();
    for (const table of Object.values(vectors)) if (table.size!==ids.size) fail();
    for (const id of fireTicks.keys()) if (!ids.has(id)) fail();
    return result;
}
function includeDirectory(root,pack) {
    return path.join(root,`modules/iterations/${pack}/resource-pack/assets/aechronis/shaders/include`);
}
function iterationAssets(root,pack,profiles) {
    const float=n=>{const rounded=Number(n.toFixed(8)); return Number.isInteger(rounded)?`${rounded}.0`:String(rounded);};
    const vec=values=>'vec3('+values.map(float).join(', ')+')';
    const values=Object.values(profiles);
    let shader='// Built combat profiles. Edit rigs in saved Blockbench projects and Build combat assets.\n';
    for (const [gun,p] of Object.entries(profiles)) shader+=`// profile ${p.id} ${gun} ${p.reload_ticks}\n`;
    for (const [predicate,select] of [['valid_profile',()=>true],['pistol',p=>p.style==='pistol'],['belt',p=>p.style==='belt'],['launcher',p=>p.style==='launcher'],['scoped',p=>p.scoped],['locks_open',p=>p.hold_open ?? (p.style==='pistol')]]) {
        const condition=values.filter(select).map(p=>`profile == ${p.id}`).join(' || ') || 'false';
        shader+=`bool aechronis_${predicate}(int profile) { return ${condition}; }\n`;
    }
    shader+='float aechronis_profile_fire_ticks(int profile) {\n';
    for (const p of values) if ((p.fire_ticks ?? 2)!==2) shader+=`    if (profile == ${p.id}) return ${float(p.fire_ticks)};\n`;
    shader+='    return 2.0;\n}\n';
    for (const [name,field] of [['hip',p=>p.hipRoot],['sight',p=>[...p.sight,p.hipRoot[2]]],['magazine',p=>p.magazine],['dominant',p=>p.dominant_hand],
        ['support',p=>p.support_hand],['muzzle',p=>p.muzzle.map(v=>mesh.round(v*256)/256)]]) {
        shader+=`vec3 aechronis_profile_${name}(int profile) {\n`;
        for (const p of values) shader+=`    if (profile == ${p.id}) return ${vec(field(p))};\n`;
        shader+='    return vec3(0.0);\n}\n';
    }
    let dispatcher='// Generated dispatcher; individual track files remain editable in Blockbench.\n';
    for (const gun of Object.keys(profiles)) dispatcher+=`#moj_import <aechronis:gun_animation_tracks_${gun.replaceAll('-','_')}.glsl>\n`;
    dispatcher+='\nint aechronis_authored_key(int profile) {\n';
    for (const [gun,p] of Object.entries(profiles)) dispatcher+=`    if (profile == ${p.id}) return int(aechronis_curve_key_${gun.replaceAll('-','_')}());\n`;
    dispatcher+='    return 0;\n}\n';
    for (const [kind,args,call] of [['pivot','int bone','bone']]) {
        dispatcher+=`\nvec3 aechronis_authored_${kind}(int profile, ${args}) {\n`;
        for (const [gun,p] of Object.entries(profiles)) dispatcher+=`    if (profile == ${p.id}) return aechronis_authored_${kind}_${gun.replaceAll('-','_')}(${call});\n`;
        dispatcher+='    return vec3(0.0);\n}\n';
    }
    const include=includeDirectory(root,pack);
    const assets=new Map([[path.join(include,'gun_profiles.glsl'),Buffer.from(shader)],[path.join(include,'gun_animation_tracks.glsl'),Buffer.from(dispatcher)]]);
    for (const [gun,p] of Object.entries(profiles)) {
        const key=gun.replaceAll('-','_'), file=path.join(include,`gun_animation_tracks_${key}.glsl`);
        if (!fs.existsSync(file)) assets.set(file,Buffer.from(`// ${gun} authored offsets over the procedural motion.\nuint aechronis_curve_key_${key}() { return 0u; }\nvec3 aechronis_authored_pivot_${key}(int bone) {\n    if (bone == 1) return ${vec(p.magazine)};\n    return vec3(8.0);\n}\n`));
    }
    return assets;
}
module.exports={readProfiles,includeDirectory,iterationAssets};
