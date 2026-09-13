'use strict';

const fs = require('node:fs');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const mesh = require('./combat-mesh.cjs');
const {clone,fail,vector,bitmap,png,ROLES,RIG_FIELDS} = mesh;
const TEMPLATES = {
    rifle:{"label":"Rifle","reloadTime":3200,"settings":{"style":"rifle","magazine":[7.825,7.8,9.5],"dominant_hand":[8.65,5.7,14.25],"support_hand":[7.83,5.9,3.9],"sight":[8,9.4],"hipRoot":[0.4,-0.2,-1.1]}},
    pistol:{"label":"Pistol","reloadTime":2200,"settings":{"style":"pistol","magazine":[8.02,0.6,12.5],"dominant_hand":[8.82,5.5,13],"support_hand":[7.02,5.3,10.7],"sight":[8.01875,10.4112],"hipRoot":[0.3,-0.27,-1.18]}},
    bolt_action:{"label":"Scoped bolt-action","reloadTime":4000,"settings":{"style":"rifle","magazine":[8,5.875,12.977],"dominant_hand":[8.8,4.7,17],"support_hand":[7.2,5.8,3],"sight":[8,9.8],"hipRoot":[0.4,-0.2,-1.1]}},
    launcher:{"label":"Launcher","reloadTime":5000,"settings":{"style":"launcher","magazine":[7.825,7.8,9.5],"dominant_hand":[8.8,5.8,10],"support_hand":[7.2,5.8,3],"sight":[6.9,10],"hipRoot":[0.4,-0.2,-1.1]}}
};
function packs(root) {
    return fs.readdirSync(path.join(root,'modules/iterations'),{withFileTypes:true})
        .filter(entry => entry.isDirectory() && !entry.name.startsWith('.'))
        .map(entry => entry.name).sort();
}
const NAMES = ['Gun','Magazine','Bolt','Dominant hand','Support hand'];
const json = value => Buffer.from(JSON.stringify(value,null,2)+'\n');
const readJSON = file => JSON.parse(fs.readFileSync(file,'utf8'));
function catalog(root,pack) {
    if (!packs(root).includes(pack)) fail('Choose an iteration from this checkout.');
    return require('./combat-profiles.cjs').readProfiles(root,pack);
}
function options(root) {
    const iterations=packs(root);
    return {templates:TEMPLATES,packs:iterations,profiles:Object.fromEntries(iterations.map(pack=>[pack,catalog(root,pack)]))};
}
function definition(value) {
    const {name,template,reloadTime}=value;
    if (typeof name!=='string' || name.length>48 || !/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(name)) fail('Gun name must use lowercase letters, digits and single hyphens (48 characters maximum).');
    if (!Object.hasOwn(TEMPLATES,template)) fail('Choose a supported animation template.');
    if (!Number.isFinite(reloadTime) || reloadTime<50 || reloadTime>12750 || reloadTime%50) fail('Reload animation duration must be 50–12750 ms in multiples of 50.');
    return {name,template,reloadTime};
}
function* filesUnder(directory) {
    if (!fs.existsSync(directory)) return;
    for (const entry of fs.readdirSync(directory,{withFileTypes:true})) {
        const file = path.join(directory,entry.name);
        if (entry.isDirectory()) yield* filesUnder(file);
        else yield file;
    }
}
function targetProfile(root,target,model) {
    if (!target || !Number.isInteger(target.id) || target.id<0 || target.id>15) fail('Choose an iteration and profile ID from 0 to 15.');
    const profiles=catalog(root,target.pack);
    const occupied=Object.entries(profiles).find(([,p])=>p.id===target.id);
    if (!Object.hasOwn(target,'expected') || target.expected!==(occupied?.[0] ?? null)) fail('The selected slot changed. Choose the export target again.');
    const gun=occupied?.[0] ?? target.name ?? model.aechronis_combat.profile;
    if (typeof gun!=='string' || gun.length>48 || !/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(gun)) fail('Choose a valid lowercase gun name.');
    if (!occupied) {
        if (profiles[gun]) fail('That gun name already uses another slot in this iteration. Select its slot or choose a different name.');
        if (fs.existsSync(require('./combat-curve-assets.cjs').curveFile(root,target.pack,gun))) fail('Gun name conflicts with an existing animation snapshot: '+gun);
        for (const file of filesUnder(path.join(root,`modules/iterations/${target.pack}/resource-pack`))) {
            const stem=path.parse(file).name;
            if (stem===gun || stem.startsWith(gun+'-')) fail('Gun name conflicts with existing assets: '+gun);
        }
    }
    return {gun,profiles,firstBuild:!occupied};
}
function importJava(root,request,plugin) {
    const weapon=definition(request.new_gun);
    const profile=weapon.name;
    const settings={...clone(TEMPLATES[weapon.template].settings),reload_ticks:weapon.reloadTime/50,
        scoped:weapon.template==='bolt_action'};
    const source = clone(request.model);
    if (!source.elements?.length) fail('Choose a Java model with elements; resolve parent-only models before importing.');
    const images = new Map(Object.entries(request.textures || {}).map(([key,value]) => [key,bitmap(value)]));
    if (!images.size) fail('Choose the source PNG textures.');
    function resolve(key,seen=new Set()) {
        if (seen.has(key)) fail('Cyclic Java texture reference');
        seen.add(key);
        const ref = source.textures?.[key] ?? key;
        if (typeof ref !== 'string') fail('Invalid Java texture reference');
        return ref.startsWith('#') ? resolve(ref.slice(1),seen) : ref;
    }
    const textureIds = Object.fromEntries([...images.keys()].map(key => [key,randomUUID()]));
    const textures = [...images].map(([key,image],i) => ({uuid:textureIds[key],id:String(i),name:key.split('/').at(-1)+'.png',
        width:image.width,height:image.height,uv_width:image.width,uv_height:image.height,internal:true,
        source:'data:image/png;base64,'+png.write(image).toString('base64')}));
    const normalization = {scale:request.scale ?? 1,offset:vector(request.offset || [0,0,0]),reverse:!!request.reverse};
    if (typeof normalization.scale !== 'number' || !Number.isFinite(normalization.scale) || normalization.scale<=0 || normalization.scale>100) fail('Import scale must be greater than zero and at most 100');
    const names = new Map();
    function groupNames(nodes,inherited='') {
        for (const node of nodes) {
            if (Number.isInteger(node)) names.set(node,inherited);
            else groupNames(node.children || [],inherited+' '+(node.name || ''));
        }
    }
    groupNames(source.groups || []);
    const elements = [], assignments = {body:[],magazine:[],bolt:[]};
    for (const [index,element] of source.elements.entries()) {
        const converted = {name:element.name || `Part ${index+1}`,type:'mesh',uuid:randomUUID(),origin:[0,0,0],rotation:[0,0,0],
            vertices:{},faces:{},shading:'flat',visibility:true,export:true};
        for (const [direction,face] of Object.entries(element.faces || {})) {
            if (face.texture == null || face.texture === false) continue;
            const key = resolve(face.texture.replace(/^#/,'')), image = images.get(key);
            if (!image) fail(`Missing PNG texture: ${key}`);
            const baked = mesh.javaFace(element,direction,face,normalization,[0,0,image.width,image.height]);
            const faceKey = `e0_${direction}`, keys = [0,1,2,3].map(i => `${faceKey}_${i}`);
            keys.forEach((key,i) => { converted.vertices[key] = baked.positions[i]; });
            converted.faces[faceKey] = {vertices:keys,uv:Object.fromEntries(keys.map((key,i) => [key,baked.uvs[i]])),texture:textureIds[key]};
        }
        if (!Object.keys(converted.faces).length) continue;
        elements.push(converted);
        const label = (converted.name+' '+(names.get(index)||'')).toLowerCase().replace(/[_-]/g,' ');
        let role = 'body';
        if (request.auto_assign !== false && settings.style !== 'launcher') {
            if (/\b(magazine|magzine|mag)\b/.test(label)) role='magazine';
            else if (/\b(bolt|slide|action)\b/.test(label)) role='bolt';
        }
        assignments[role].push(converted.uuid);
    }
    if (!elements.length) fail('The source model has no visible textured faces');
    const vertices = elements.flatMap(e => Object.values(e.vertices)), front = vertices.reduce((min,p) => Math.min(min,p[2]),Infinity);
    const tip = vertices.filter(p => Math.abs(p[2]-front)<.001), muzzle = [0,1,2].map(i => tip.reduce((sum,p) => sum+p[i],0)/tip.length);
    const origins = [[8,6,12],settings.magazine,[8,8,8],settings.dominant_hand,settings.support_hand];
    const groups = ROLES.map((role,i) => ({uuid:randomUUID(),name:NAMES[i],aechronis_combat_role:role,origin:origins[i],rotation:[0,0,0],export:true}));
    const children = [...assignments.body,...groups.slice(1).map(g => ({uuid:g.uuid,children:assignments[g.aechronis_combat_role] || []}))];
    const config = {version:1,profile,reload_ticks:settings.reload_ticks,profile_settings:settings,import_settings:normalization,
        template:weapon.template,java_source:{model:request.model,textures:clone(request.textures)},
        rig:{...Object.fromEntries(RIG_FIELDS.filter(key => key in settings).map(key => [key,settings[key]])),muzzle},display:source.display || {}};
    const animations = Object.entries(plugin.CLIPS).map(([clip,info]) => {
        const length = (info.reload ? settings.reload_ticks : info.ticks)/20;
        return {uuid:randomUUID(),name:clip,aechronis_combat_clip:clip,loop:info.loop?'loop':'once',override:false,length,snapping:120,
            animators:Object.fromEntries(groups.map(g => [g.uuid,{name:g.name,type:'bone',keyframes:
                (g.aechronis_combat_role.endsWith('_hand')?['position']:['position','rotation']).flatMap(channel => [0,length].map(time =>
                    ({uuid:randomUUID(),channel,time,data_points:[{x:0,y:0,z:0}],interpolation:'linear'})))}]))};
    });
    return {meta:{format_version:'5.0',model_format:'aechronis_combat_animation',box_uv:false},name:`${profile} combat model`,
        resolution:{width:textures[0].width,height:textures[0].height},aechronis_combat:config,groups,elements,textures,
        outliner:[{uuid:groups[0].uuid,children}],animations};
}
function fallbackAssets(root,model,settings) {
    const gun = model.aechronis_combat.profile, original = model.aechronis_combat.java_source;
    if (!original?.model?.elements?.length) fail('New gun project needs its imported Java source for static fallback models.');
    const source=clone(original.model), assets = new Map(), refs = new Map();
    const pack=path.join(root,`modules/iterations/${settings.pack}/resource-pack`);
    Object.entries(original.textures).forEach(([ref,source],index) => {
        const suffix=index?`-texture-${index}`:'';
        refs.set(ref,`aechronis:item/${gun}${suffix}`);
        assets.set(path.join(pack,`assets/aechronis/textures/item/${gun}${suffix}.png`),png.write(bitmap(source)));
    });
    source.textures ||= {};
    for (const [key,ref] of Object.entries(source.textures)) if (!ref.startsWith('#')) source.textures[key]=refs.get(ref) || refs.values().next().value;
    for (const element of source.elements) for (const face of Object.values(element.faces || {})) {
        const ref=face.texture || '';
        if (!ref.startsWith('#')) {
            if (!refs.has(ref)) fail(`Missing static fallback texture: ${ref}`);
            const key=`combat_texture_${Object.keys(source.textures).length}`;
            source.textures[key]=refs.get(ref); face.texture='#'+key;
        }
    }
    delete source.parent;
    assets.set(path.join(pack,`assets/aechronis/models/item/${gun}.json`),json(source));
    const items={};
    for (const suffix of ['','-empty','-reloading','-aiming']) {
        const item={model:{type:'minecraft:model',model:`aechronis:item/${gun}`}};
        items[gun+suffix]=item;
        assets.set(path.join(pack,`assets/aechronis/items/${gun}${suffix}.json`),json(item));
    }
    return {assets,items};
}
function animatedItem(gun,source,aimed,equip,empty,reloading=false,locksOpen=false) {
    if (source.model?.type==='minecraft:select' && source.model.property==='minecraft:display_context'
        && source.model.cases?.some(entry=>(entry.model?.model ?? entry.model?.fallback?.model)?.startsWith(`aechronis:item/${gun}-animation-`))) {
        source={...source,model:source.model.fallback};
    }
    const pose=aimed?'aim':'hip';
    const reference=(mode,isEmpty=empty)=>({type:'minecraft:model',model:`aechronis:item/${gun}-animation-${mode}${isEmpty?'-empty':''}`,
        tints:[{type:'minecraft:custom_model_data',index:0,default:16777215}]});
    // Ammo is encoded as raw item damage; 99 is empty. Keep the bolt/slide locked
    // through an empty reload, while a magazine top-up starts with it closed.
    const firstPerson=mode=>reloading && locksOpen ? {type:'minecraft:range_dispatch',property:'minecraft:damage',normalize:false,
        entries:[{threshold:99,model:reference(mode,true)}],fallback:reference(mode,false)} : reference(mode);
    return {...source,hand_animation_on_swap:equip,swap_animation_scale:1,model:{type:'minecraft:select',property:'minecraft:display_context',
        cases:[{when:'firstperson_righthand',model:firstPerson(`${pose}-right`)},{when:'firstperson_lefthand',model:firstPerson(`${pose}-left`)}],fallback:source.model}};
}
function buildAssets(root,model,source,firstBuild,tracks) {
    const gun=model.aechronis_combat.profile, settings=source.settings, pack=path.join(root,`modules/iterations/${settings.pack}/resource-pack`);
    const {assets,items} = firstBuild ? fallbackAssets(root,model,settings) : {assets:new Map(),items:{}};
    const plugin=require('./aechronis_combat_animation.js'), curveAssets=require('./combat-curve-assets.cjs');
    const curveData=plugin.extractCurveData(tracks);
    assets.set(curveAssets.curveFile(root,settings.pack,gun),json(curveData));
    const baked=mesh.bakeAtlas(gun,source,curveData);
    for (const [file,content] of curveAssets.iterationAssets(root,settings.pack,assets)) assets.set(file,content);
    for (const [name,carrier] of Object.entries(baked.models)) {
        const {elements,...header}=carrier;
        const prefix=JSON.stringify(header,null,2).trimEnd().slice(0,-1).trimEnd();
        assets.set(path.join(pack,`assets/aechronis/models/item/${gun}-animation-${name}.json`),
            Buffer.from(prefix+',\n  "elements": [\n    '+elements.map(e=>JSON.stringify(e)).join(',\n    ')+'\n  ]\n}\n'));
    }
    assets.set(path.join(pack,`assets/aechronis/textures/item/${gun}-animation.png`),baked.atlas);
    if (!firstBuild) for (const suffix of ['','-empty','-reloading','-aiming']) {
        const file=path.join(pack,`assets/aechronis/items/${gun}${suffix}.json`);
        if (fs.existsSync(file)) items[gun+suffix]=readJSON(file);
    }
    for (const name of Object.keys(items).sort()) {
        const locksOpen=settings.hold_open ?? (settings.style==='pistol');
        const empty=locksOpen && name===gun+'-empty';
        assets.set(path.join(pack,`assets/aechronis/items/${name}.json`),json(animatedItem(gun,items[name],name===gun+'-aiming',false,empty,name===gun+'-reloading',locksOpen)));
        if ([gun,gun+'-empty'].includes(name)) assets.set(path.join(pack,`assets/aechronis/items/${name}-equip.json`),json(animatedItem(gun,items[name],false,true,empty)));
    }
    return {assets,baked};
}
module.exports={TEMPLATES,packs,json,readJSON,catalog,options,definition,targetProfile,importJava,buildAssets};
