'use strict';

// Runs with desktop Blockbench's bundled Node runtime, or as a standalone CLI.
// Imported project data never supplies executable paths. Publication stays on
// this process until commit/rollback finishes; the editor never terminates it.
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const {randomUUID,createHash}=require('node:crypto');
const mesh=require('./combat-mesh.cjs');
const project=require('./combat-project.cjs');
const profiles=require('./combat-profiles.cjs');
const plugin=require('./aechronis_combat_animation.js');
const ROOT=path.resolve(__dirname,'../../../..');

function destination(root,file) {
    const absolute=path.resolve(file), relative=path.relative(root,absolute);
    if (!relative || relative==='..' || relative.startsWith('..'+path.sep) || path.isAbsolute(relative)) mesh.fail(`Build destination escapes the repository: ${file}`);
    let current=root;
    for (const part of relative.split(path.sep)) {
        current=path.join(current,part);
        try { if (fs.lstatSync(current).isSymbolicLink()) mesh.fail(`Build destination is a symbolic link: ${current}`); }
        catch (error) { if (error.code!=='ENOENT') throw error; }
    }
    if (/\.kts?$/i.test(absolute)) mesh.fail('Combat asset builds cannot write Kotlin files. Define and register weapons manually.');
    return absolute;
}
function commitAssets(root,assets,io=fs) {
    const changes=new Map(),before=new Map(),staged=new Map(),applied=[];
    // Validate every destination before creating any files or directories.
    for (const [file,content] of assets) {
        destination(root,file);
        const old=io.existsSync(file)?io.readFileSync(file):null;
        if (content===null ? old===null : old?.equals(content)) continue;
        changes.set(file,content); before.set(file,old);
    }
    try {
        for (const [file,content] of changes) {
            if (content===null) continue;
            io.mkdirSync(path.dirname(file),{recursive:true});
            const temporary=path.join(path.dirname(file),'.combat-build-'+randomUUID());
            staged.set(file,temporary);
            io.writeFileSync(temporary,content,{flag:'wx'});
        }
        for (const [file,content] of changes) {
            destination(root,file);
            if (content===null) io.unlinkSync(file); else io.renameSync(staged.get(file),file);
            applied.push(file);
        }
    } catch (error) {
        const failures=[];
        for (const file of applied.reverse()) {
            try { if (before.get(file)===null) io.unlinkSync(file); else io.writeFileSync(file,before.get(file)); }
            catch (rollback) { failures.push(`${file}: ${rollback.message}`); }
        }
        if (failures.length) throw new Error(`${error.message}\nRollback needs attention:\n${failures.join('\n')}`);
        throw error;
    } finally {
        for (const temporary of staged.values()) if (io.existsSync(temporary)) io.unlinkSync(temporary);
    }
    return [...changes.keys()].map(file=>path.relative(root,file));
}
async function withBuildLock(root,work) {
    const lock=path.join(os.tmpdir(),'aechronis-combat-js-'+createHash('sha256').update(root).digest('hex').slice(0,16)+'.lock');
    const token=randomUUID(), deadline=Date.now()+30000;
    while (true) {
        let fd;
        try {
            fd=fs.openSync(lock,'wx');
            fs.writeFileSync(fd,JSON.stringify({pid:process.pid,token}));
            fs.closeSync(fd);
            break;
        } catch (error) {
            if (fd!==undefined) { fs.closeSync(fd); fs.unlinkSync(lock); throw error; }
            if (error.code!=='EEXIST') throw error;
            // A crashed process cannot release its lock. Only reclaim a complete,
            // unchanged record whose owner no longer exists (never a live build).
            try {
                const owner=fs.readFileSync(lock,'utf8'), value=JSON.parse(owner);
                try { process.kill(value.pid,0); }
                catch (status) {
                    if (status.code==='ESRCH' && fs.readFileSync(lock,'utf8')===owner) fs.unlinkSync(lock);
                }
            } catch (_) { /* Another process may still be writing its lock. */ }
            if (Date.now()>deadline) throw new Error(`Another combat build is still running. Try again after it finishes. Lock: ${lock}`);
            await new Promise(resolve=>setTimeout(resolve,100));
        }
    }
    try { return await work(); }
    finally {
        if (fs.existsSync(lock) && JSON.parse(fs.readFileSync(lock,'utf8')).token===token) fs.unlinkSync(lock);
    }
}
function buildLocked(root,input,target) {
    const model=mesh.clone(input), config=model.aechronis_combat;
    const {gun,profiles:catalog,firstBuild}=project.targetProfile(root,target,model);
    config.profile=gun;
    const settings={...config.profile_settings,id:target.id,pack:target.pack,reload_ticks:config.reload_ticks};
    const tracks=plugin.extractProject(model), shader=plugin.compileShader(tracks), warnings=tracks.warnings;
    const source=mesh.projectSource(model,settings);
    const {assets}=project.buildAssets(root,model,source,firstBuild,tracks);
    catalog[gun]=source.settings;
    for (const [file,content] of profiles.iterationAssets(root,target.pack,catalog)) assets.set(file,content);
    const include=profiles.includeDirectory(root,source.settings.pack);
    assets.set(path.join(include,`gun_animation_tracks_${gun.replaceAll('-','_')}.glsl`),Buffer.from(shader));
    const files=commitAssets(root,assets);
    return {files,faces:source.faces.length,warnings,target:{pack:target.pack,id:target.id,name:gun},reload_ticks:config.reload_ticks,fire_ticks:settings.fire_ticks ?? 2};
}
async function request(input,root=ROOT) {
    root=fs.realpathSync(root);
    if (input.action==='options') return project.options(root);
    if (input.action==='import') return {model:project.importJava(root,input,plugin)};
    if (input.action==='build') return withBuildLock(root,()=>buildLocked(root,input.model,input.target));
    if (input.action==='edit_profiles') return withBuildLock(root,()=>{
        const {assets,changes}=require('./edit-profiles.cjs').editAssets(root,input);
        return {files:commitAssets(root,assets),changes};
    });
    mesh.fail('Expected an options, import, build or edit_profiles request');
}
module.exports={request,buildLocked,commitAssets,destination,withBuildLock};
if (require.main===module) {
    let input='';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data',chunk=>{input+=chunk;});
    process.stdin.on('end',()=>Promise.resolve().then(()=>request(JSON.parse(input))).then(
        result=>process.stdout.write(JSON.stringify(result)+'\n'),error=>{process.stderr.write(error.message+'\n');process.exitCode=1;}));
}
