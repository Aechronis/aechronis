'use strict';

const fs=require('node:fs');
const path=require('node:path');
const project=require('./combat-project.cjs');
const profiles=require('./combat-profiles.cjs');
const mesh=require('./combat-mesh.cjs');
const curveAssets=require('./combat-curve-assets.cjs');

// Prepare the whole edit before publishing, so ID/name swaps are simultaneous.
function editAssets(root,input) {
    const catalog=project.catalog(root,input.pack);
    if (JSON.stringify(catalog)!==JSON.stringify(input.expected)) mesh.fail('Profiles changed since this editor opened. Reopen it and try again.');
    const entries=Object.entries(catalog), edits=input.profiles;
    if (!Array.isArray(edits) || edits.length!==entries.length || new Set(edits.map(e=>e.from)).size!==entries.length || edits.some(e=>!Object.hasOwn(catalog,e.from))) mesh.fail('Supply every existing profile exactly once.');
    const ids=new Set(),names=new Set(),next={};
    for (const edit of edits) {
        if (edit.delete===true) continue;
        if (!Number.isInteger(edit.id) || edit.id<0 || edit.id>15 || ids.has(edit.id)) mesh.fail('Each profile needs a different ID from 0 to 15.');
        if (typeof edit.name!=='string' || (edit.name.length>48 || !/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(edit.name)) || names.has(edit.name)) mesh.fail('Use unique lowercase gun names (letters, numbers and hyphens, up to 48 characters).');
        ids.add(edit.id);names.add(edit.name);
        next[edit.name]={...catalog[edit.from],id:edit.id};
    }
    const changed=edits.filter(e=>e.delete===true || e.name!==e.from || e.id!==catalog[e.from].id);
    if (!changed.length) return {assets:new Map(),changes:[]};
    const base=path.join(root,'modules/iterations',input.pack,'resource-pack');
    const files=[];
    function walk(dir) {for(const entry of fs.readdirSync(dir,{withFileTypes:true})){
        const file=path.join(dir,entry.name);
        if(entry.isSymbolicLink()) mesh.fail('Profile edits cannot follow symbolic links: '+file);
        if(entry.isDirectory())walk(file);else files.push(file);
    }}
    walk(base);
    const byName=new Map(edits.map(e=>[e.from,e])), ordered=entries.map(([gun])=>gun).sort((a,b)=>b.length-a.length);
    const moves=new Map(),refs=new Map(),assets=new Map(),deleted=new Set(),handEdits=new Map();
    for(const file of files) {
        const relative=path.relative(base,file).replaceAll(path.sep,'/');
        const match=relative.match(/^(.*assets\/aechronis\/(?:items|models\/item|textures\/item)\/)([^/]+)(\.(?:json|png)(?:\.mcmeta)?)$/);
        if(!match)continue;
        const gun=ordered.find(g=>match[2]===g || match[2].startsWith(g+'-'));
        if(!gun)continue;
        const edit=byName.get(gun);
        if(edit.delete===true){deleted.add(file);continue;}
        const stem=edit.name+match[2].slice(gun.length);
        const target=path.join(base,match[1]+stem+match[3]);
        moves.set(file,target);
        if(edit.id!==catalog[gun].id && match[1].endsWith('/models/item/') && match[2].startsWith(gun+'-animation-') && match[3]==='.json') {
            handEdits.set(file,{from:catalog[gun].id,to:edit.id});
        }
        if(match[3]==='.json' || match[3]==='.png') {
            const prefix=match[1].includes('/items/')?'aechronis:':'aechronis:item/';
            refs.set(prefix+match[2],prefix+stem);
        }
    }
    // Published curve snapshots move with profiles but are not client assets.
    for (const edit of edits) {
        const file=curveAssets.curveFile(root,input.pack,edit.from);
        if (!fs.existsSync(file)) mesh.fail('Missing animation snapshot: '+file);
        if (fs.lstatSync(file).isSymbolicLink()) mesh.fail('Profile edits cannot follow symbolic links: '+file);
        files.push(file);
        if (edit.delete===true) deleted.add(file);
        else moves.set(file,curveAssets.curveFile(root,input.pack,edit.name));
    }
    const destinations=new Set();
    for(const [file,target] of moves){
        if(destinations.has(target) || (file!==target && fs.existsSync(target) && !moves.has(target) && !deleted.has(target))) mesh.fail('Asset name already exists: '+target);
        destinations.add(target);
    }
    function rewrite(value){
        if(typeof value==='string')return refs.get(value)||value;
        if(Array.isArray(value))return value.map(rewrite);
        if(value && typeof value==='object')return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,rewrite(v)]));
        return value;
    }
    // Update references in other item definitions too (for example composite items).
    for(const file of files.filter(f=>f.endsWith('.json') && !deleted.has(f))) {
        const old=fs.readFileSync(file), value=JSON.parse(old), updated=rewrite(value);
        const handEdit=handEdits.get(file);
        if(handEdit && updated.textures?.hands===mesh.HAND_TEXTURE) {
            // Hand headers live in this iteration's atlas. Remap the original model's
            // UVs before moving it, so simultaneous ID/name swaps apply once.
            const coordinates=new Map();
            for(let mode=0;mode<4;mode++)for(let face=0;face<24;face++) {
                const uv=id=>{
                    const [x,y]=mesh.handRecord(id,mode,face);
                    const u=(x+.5)*16/1024,v=(y+.5)*16/512;
                    return [u,v,u,v];
                };
                coordinates.set(JSON.stringify(uv(handEdit.from)),uv(handEdit.to));
            }
            for(const element of updated.elements || [])for(const face of Object.values(element.faces || {})) {
                if(face.texture!=='#hands')continue;
                const uv=coordinates.get(JSON.stringify(face.uv));
                if(!uv)mesh.fail('Unexpected hand UVs in '+file);
                face.uv=uv;
            }
        }
        if(JSON.stringify(value)!==JSON.stringify(updated))assets.set(moves.get(file)||file,project.json(updated));
    }
    for(const [file,target] of moves)if(!assets.has(target))assets.set(target,fs.readFileSync(file));
    for(const [file,target] of moves)if(file!==target && !destinations.has(file))assets.set(file,null);
    for(const file of deleted)if(!destinations.has(file))assets.set(file,null);
    const include=profiles.includeDirectory(root,input.pack),tracks=new Map();
    for(const edit of edits) {
        if(edit.delete===true)continue;
        const oldKey=edit.from.replaceAll('-','_'),key=edit.name.replaceAll('-','_');
        const oldFile=path.join(include,`gun_animation_tracks_${oldKey}.glsl`),file=path.join(include,`gun_animation_tracks_${key}.glsl`);
        if(file!==oldFile && fs.existsSync(file) && !edits.some(e=>e.from.replaceAll('-','_')===key))mesh.fail('Animation shader already exists: '+file);
        const text=fs.readFileSync(oldFile,'utf8')
            .replace(new RegExp('\\baechronis_authored_pivot_'+oldKey+'\\b','g'),`aechronis_authored_pivot_${key}`)
            .replace(new RegExp('\\baechronis_curve_key_'+oldKey+'\\b','g'),`aechronis_curve_key_${key}`);
        tracks.set(file,Buffer.from(text));
        const atlas=path.join(base,'assets/aechronis/textures/item',edit.from+'-animation.png');
        if(edit.id!==catalog[edit.from].id) {
            const bitmap=mesh.png.read(fs.readFileSync(atlas)),data=bitmap.data;
            let records=0;
            for(let y=0;y<bitmap.height;y++)for(let x=0;x+2<bitmap.width;x+=32){
                const p=(y*bitmap.width+x)*4;
                if(data[p]!==17||data[p+1]!==143||data[p+2]!==81||data[p+3]!==255)continue;
                if(data[p+4]*256+data[p+5]!==x||data[p+6]*256+data[p+7]!==y)continue;
                const bits=data[p+10],id=(bits&1)|((bits>>2)<<1);
                if(id!==catalog[edit.from].id)mesh.fail('Atlas contains a different profile ID: '+atlas);
                data[p+10]=(bits&2)|(edit.id&1)|((edit.id>>1)<<2);records++;
            }
            if(!records)mesh.fail('No profile records found in '+atlas);
            assets.set(moves.get(atlas)||atlas,mesh.png.write(bitmap));
        }
    }
    for(const edit of edits){const file=path.join(include,`gun_animation_tracks_${edit.from.replaceAll('-','_')}.glsl`);if(!tracks.has(file))assets.set(file,null);}
    for(const [file,content] of profiles.iterationAssets(root,input.pack,next))assets.set(file,content);
    for(const [file,content] of tracks)assets.set(file,content);
    for(const [file,content] of curveAssets.iterationAssets(root,input.pack,assets))assets.set(file,content);
    return {assets,changes:changed.map(e=>({from:e.from,oldId:catalog[e.from].id,...(e.delete===true?{deleted:true}:{name:e.name,id:e.id})}))};
}
module.exports={editAssets};
