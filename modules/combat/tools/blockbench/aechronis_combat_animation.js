(function () {
    'use strict';

    const ID = 'aechronis_combat_animation';
    const ROLES = {body: 0, magazine: 1, bolt: 2, dominant_hand: 3, support_hand: 4};
    const CLIPS = {
        idle: {id: 0, name: 'Idle', ticks: 25, loop: true},
        fire: {id: 1, name: 'Fire', ticks: 2},
        reload: {id: 2, name: 'Reload', reload: true},
        aim_in: {id: 3, name: 'Aim in', ticks: 7},
        aim_out: {id: 4, name: 'Aim out', ticks: 7},
        reload_aim: {id: 5, name: 'Reload from ADS', reload: true},
        walk: {id: 8, name: 'Walk', ticks: 25, loop: true},
        sprint: {id: 9, name: 'Sprint', ticks: 12.5, loop: true}
    };
    const zero = [0, 0, 0];
    const same = (a, b) => a.every((v, i) => Math.abs(v - b[i]) < 1e-7);
    const lengthFor = (clip, config) => (CLIPS[clip].reload ? config.reload_ticks :
        clip === 'fire' ? (config.profile_settings?.fire_ticks ?? 2) : CLIPS[clip].ticks) / 20;
    const fail = message => { throw new Error(message); };

    function profileFor(config) {
        if (!config || typeof config.profile !== 'string' || !/^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$/.test(config.profile)) fail('Invalid combat gun name.');
        const profile = config.profile_settings;
        if (!profile ||
            !['rifle', 'pistol', 'belt', 'launcher'].includes(profile.style)) fail('Missing saved profile settings.');
        const result = {...profile};
        if (!Number.isInteger(profile.fire_ticks ?? 2) || (profile.fire_ticks ?? 2) < 1 || (profile.fire_ticks ?? 2) > 255) fail('Fire duration must be 1–255 ticks.');
        for (const [key, value] of Object.entries(config.rig || {})) {
            if (!['magazine', 'dominant_hand', 'support_hand', 'sight', 'hipRoot', 'muzzle'].includes(key)) fail(`Unknown rig setting: ${key}`);
            if (key === 'sight') {
                if (!Array.isArray(value) || value.length !== 2) fail('Sight needs X and Y coordinates.');
                result[key] = value.map(n => numeric(n, 'Sight'));
            } else result[key] = vector(value, key);
        }
        return result;
    }

    function numeric(value, where) {
        // Blockbench's empty numeric fields mean zero. Do not evaluate Molang or JavaScript.
        if (value === '' || value === undefined) return 0;
        if (typeof value !== 'number' && (typeof value !== 'string' ||
            !/^[+-]?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?$/i.test(value.trim()))) {
            fail(`${where}: use a number, not an expression.`);
        }
        const result = Number(value);
        if (!Number.isFinite(result) || Math.abs(result) > 10000) fail(`${where}: invalid or excessive number.`);
        return result;
    }

    function vector(value, where) {
        if (!Array.isArray(value) || value.length !== 3) fail(`${where}: expected three coordinates.`);
        return value.map((n, i) => numeric(n, `${where} ${'XYZ'[i]}`));
    }

    function readTrack(keyframes, channel, length, label) {
        const keys = keyframes.filter(key => key.channel === channel).map(key => {
            const time = numeric(key.time, `${label} time`);
            if (time < 0 || time > length + 1e-7) fail(`${label}: key at ${time}s is outside the ${length}s clip.`);
            const interpolation = key.interpolation || 'linear';
            if (!['linear', 'step'].includes(interpolation)) {
                fail(`${label}: ${interpolation} is unsupported. Use Linear or Step interpolation.`);
            }
            if (!Array.isArray(key.data_points) || key.data_points.length !== 1) {
                fail(`${label}: each key must have one XYZ value (no pre/post values).`);
            }
            const point = key.data_points[0];
            const value = ['x', 'y', 'z'].map(axis => numeric(point[axis], `${label} ${axis}`));
            return {time: Math.min(time / length, 1), value, interpolation};
        }).sort((a, b) => a.time - b.time);
        if (keys.length > 96) fail(`${label}: use at most 96 keys per channel.`);
        for (let i = 1; i < keys.length; i++) {
            if (keys[i].time - keys[i - 1].time < 1e-7) fail(`${label}: two keys share the same time.`);
        }
        return keys;
    }

    function extractProject(model) {
        const version = Number(model.meta?.format_version);
        if (model.meta?.model_format !== ID || !Number.isFinite(version) || version < 5) {
            fail('Open an Aechronis combat project saved in Blockbench 5+.');
        }
        const config = model.aechronis_combat;
        if (!config || config.version !== 1) fail('Missing or unsupported combat profile.');
        if (!Number.isInteger(config.reload_ticks) || config.reload_ticks < 1 || config.reload_ticks > 255) {
            fail('Reload duration must be an integer from 1 to 255 ticks.');
        }
        const groups = model.groups || [];
        const roles = {};
        const uuids = new Set();
        for (const group of groups) {
            const role = group.aechronis_combat_role;
            if (!Object.hasOwn(ROLES, role) || roles[role]) fail('The rig needs exactly one of each combat bone.');
            if (typeof group.uuid !== 'string' || !group.uuid || uuids.has(group.uuid)) fail('Combat bones must have distinct UUIDs.');
            uuids.add(group.uuid);
            if (!same(vector(group.rotation || zero, `${role} rest rotation`), zero)) fail(`${role}: rest rotation must remain zero; animate rotation on the timeline.`);
            roles[role] = group;
        }
        if (Object.keys(roles).length !== 5) fail('Missing combat bones. The body, magazine, bolt and both hand targets are required.');
        if (!same(vector(roles.body.origin, 'Body pivot'), [8, 6, 12])) fail('The body pivot must stay at [8, 6, 12].');
        const settings = profileFor(config);
        const handOrigins = [settings.dominant_hand, settings.support_hand];
        for (const [index, role] of ['dominant_hand', 'support_hand'].entries()) {
            if (!same(vector(roles[role].origin, `${role} origin`), handOrigins[index])) fail(`${role}: keep its rest origin fixed; move the target with Position keyframes.`);
        }
        const parents = {};
        function walk(nodes, parent) {
            for (const node of nodes || []) {
                const uuid = typeof node === 'string' ? node : node.uuid;
                if (Object.hasOwn(parents, uuid)) fail('Duplicate node in the rig hierarchy.');
                parents[uuid] = parent;
                if (typeof node === 'object') walk(node.children, uuid);
            }
        }
        walk(model.outliner, null);
        for (const [role, group] of Object.entries(roles)) {
            if (parents[group.uuid] !== (role === 'body' ? null : roles.body.uuid)) {
                fail(`${role}: keep the four part/hand bones directly beneath the body bone.`);
            }
        }
        const byUuid = Object.fromEntries(Object.entries(roles).map(([role, group]) => [group.uuid, role]));
        const result = {profile: config.profile, reload_ticks: config.reload_ticks, fire_ticks: settings.fire_ticks ?? 2, pivots: {}, clips: {}, warnings: []};
        for (const role of ['magazine', 'bolt']) result.pivots[ROLES[role]] = vector(roles[role].origin, `${role} pivot`);
        let count = 0;
        for (const animation of model.animations || []) {
            const clip = animation.aechronis_combat_clip;
            if (!Object.hasOwn(CLIPS, clip)) fail(`${animation.name || 'Animation'}: unsupported runtime action. Use the original combat clips from your saved project.`);
            if (result.clips[clip]) fail(`More than one animation is assigned to ${CLIPS[clip].name}. Keep one per clip.`);
            const length = lengthFor(clip, config);
            if (Math.abs(numeric(animation.length, `${clip} duration`) - length) > 1e-7) {
                fail(`${CLIPS[clip].name} must last ${length}s (${length * 20} ticks). Restore this duration in the animation timeline.`);
            }
            if (animation.loop !== (CLIPS[clip].loop ? 'loop' : 'once')) fail(`${clip}: loop mode must be ${CLIPS[clip].loop ? 'Loop' : 'Play Once'}.`);
            for (const property of ['anim_time_update', 'start_delay', 'loop_delay']) {
                if (animation[property] !== undefined && String(animation[property]).trim() !== '') fail(`${clip}: ${property} is unsupported.`);
            }
            if (animation.blend_weight !== undefined && !['', '1'].includes(String(animation.blend_weight).trim())) fail(`${clip}: use the default blend weight of 1.`);
            if (animation.override) fail(`${clip}: disable Override. Combat clips add offsets to the built-in shader motion.`);
            const tracks = [];
            for (const [uuid, animator] of Object.entries(animation.animators || {})) {
                const keys = animator.keyframes || [];
                if (!keys.length) continue;
                const role = byUuid[uuid];
                if (!role || (animator.type && animator.type !== 'bone')) fail(`${clip}: only the five combat bones can be animated; effects and extra bones cannot be exported.`);
                if (animator.rotation_global || animator.quaternion_interpolation) fail(`${clip}/${role}: use local Euler XYZ rotation.`);
                if (Object.values(animator.muted || {}).some(Boolean)) fail(`${clip}/${role}: unmute channels before exporting.`);
                for (const key of keys) if (!['position', 'rotation', 'scale'].includes(key.channel)) fail(`${clip}/${role}: unsupported ${key.channel} channel.`);
                for (const channel of ['position', 'rotation', 'scale']) {
                    const label = `${clip}/${role}/${channel}`;
                    const track = readTrack(keys, channel, length, label);
                    if (channel === 'scale') {
                        if (track.some(key => !same(key.value, [1, 1, 1]))) fail(`${label}: scaling is not supported by the shader rig.`);
                        continue;
                    }
                    if (role.endsWith('_hand') && channel === 'rotation' && track.some(key => !same(key.value, zero))) {
                        fail(`${label}: animate hand target Position only. The shader keeps each arm rigid and points it toward the shoulder.`);
                    }
                    const reference = readTrack(animation.aechronis_combat_reference?.[role] || [], channel, length, `${label} reference`);
                    if (!track.length && reference.length) fail(`${label}: keep at least one key; use zero values to remove the built-in movement.`);
                    const times = [...new Set([...track, ...reference].map(key => key.time))];
                    // bbmodel saves numeric reference values to five decimals,
                    // but native key coordinates are strings and retain precision.
                    // Ignore only that rounding noise when identifying untouched
                    // reference motion.
                    const matchesReference = times.every(time => sampleTrack(track, time).every((value, axis) =>
                        Math.abs(value - sampleTrack(reference, time)[axis]) <= 0.00000501));
                    if (!times.length || matchesReference && track.every(key => key.interpolation === 'linear')) continue;
                    count += track.length + reference.length;
                    const offsetAt = time => sampleTrack(track, time).map((value, axis) => value - sampleTrack(reference, time)[axis]);
                    if (!same(offsetAt(0), offsetAt(1)) && CLIPS[clip].loop) result.warnings.push(`${label}: loop endpoints differ and will jump.`);
                    if (!CLIPS[clip].loop && !same(offsetAt(1), zero)) result.warnings.push(`${label}: final offset is nonzero and will snap when the server clears the action.`);
                    tracks.push({bone: ROLES[role], channel: channel === 'position' ? 0 : 1, keys: track, reference});
                }
            }
            result.clips[clip] = tracks;
        }
        if (count > 2048) fail('This project exceeds the 2048-key shader export limit. Simplify the curves.');
        return result;
    }

    function float(value) {
        const number = Number(value.toFixed(9));
        return Number.isInteger(number) ? `${number}.0` : String(number).replace(/e([+-]?\d+)$/, 'e$1');
    }
    const vec = value => `vec3(${value.map(float).join(', ')})`;

    function sampleTrack(keys, time, epsilon = 0) {
        if (!keys.length) return zero.slice();
        // BoneAnimator snaps to a nearby key before interpolating. Match that
        // editor-only tolerance when removing its displayed reference curve.
        if (epsilon) {
            const before = keys.findLast(key => key.time < time);
            const after = keys.find(key => key.time >= time);
            if (before && Math.abs(before.time - time) <= epsilon) return before.value.slice();
            if (after && Math.abs(after.time - time) <= epsilon) return after.value.slice();
        }
        if (time <= keys[0].time) return keys[0].value.slice();
        for (let i = 1; i < keys.length; i++) {
            const a = keys[i - 1], b = keys[i];
            if (time < b.time) return a.interpolation === 'step' ? a.value.slice() :
                a.value.map((value, axis) => value + (b.value[axis] - value) * (time - a.time) / (b.time - a.time));
        }
        return keys.at(-1).value.slice();
    }
    // Stable across object insertion order; only the root content key is omitted.
    function canonicalCurveData(data) {
        const ordered = value => Array.isArray(value) ? value.map(ordered) :
            value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, ordered(value[key])])) : value;
        const {key, ...content} = data;
        return JSON.stringify(ordered(content));
    }

    function curveDataKey(data) {
        // Curve data contains only ASCII field names and numeric values.
        let hash = 2166136261;
        for (const character of canonicalCurveData(data)) hash = Math.imul(hash ^ character.charCodeAt(0), 16777619) >>> 0;
        return (hash & 0xffffff) || 1;
    }

    function extractCurveData(project) {
        const starts = [], ends = [], ranges = new Map(), tracks = [];
        const leftLimit = (keys, time) => {
            const previous = keys.findLast(key => key.time < time);
            return previous?.interpolation === 'step' ? previous.value : sampleTrack(keys, time);
        };
        const difference = (a, b) => a.map((value, axis) => Number((value - b[axis]).toFixed(9)));
        const clips = Object.entries(project.clips).sort(([a], [b]) => CLIPS[a].id - CLIPS[b].id);
        for (const [clip, channels] of clips) {
            for (const track of [...channels].sort((a, b) => a.bone - b.bone || a.channel - b.channel)) {
                const keys = track.keys || [], reference = track.reference || [];
                const times = [...new Set([...keys, ...reference].map(key => key.time))].sort((a, b) => a - b);
                if (!times.length) continue;
                let segments = [];
                for (let index = 0; index < times.length; index++) {
                    const start = times[index], end = times[index + 1] ?? start;
                    const a = difference(sampleTrack(keys, start), sampleTrack(reference, start));
                    // Keep the old value on a step's open left interval and its
                    // new value at the next segment's right-continuous start.
                    const b = end === start ? a : difference(leftLimit(keys, end), leftLimit(reference, end));
                    const previous = segments.at(-1);
                    if (previous && vec(previous.a) === vec(previous.b) && vec(a) === vec(b) && vec(previous.b) === vec(a)) {
                        previous.end = end;
                    } else segments.push({start, end, a, b});
                }
                if (segments.every(segment => vec(segment.a) === vec(segments[0].a) && vec(segment.b) === vec(segments[0].a))) {
                    if (vec(segments[0].a) === vec(zero)) continue;
                    segments = [{start: 0, end: 0, a: segments[0].a, b: segments[0].a}];
                }
                const a = segments.map(segment => [...segment.a, segment.start]);
                const b = segments.map(segment => [...segment.b, segment.end]);
                const signature = JSON.stringify([a, b]);
                let range = ranges.get(signature);
                if (!range) {
                    range = {first: starts.length, count: segments.length};
                    ranges.set(signature, range);
                    starts.push(...a); ends.push(...b);
                }
                tracks.push({clip: CLIPS[clip].id, bone: track.bone, channel: track.channel, ...range});
            }
        }
        const pivots = Object.fromEntries(Object.entries(project.pivots).sort(([a], [b]) => Number(a) - Number(b)).map(([bone, pivot]) => [bone, pivot.slice()]));
        const result = {version: 1, starts, ends, tracks, pivots};
        return {version: 1, key: curveDataKey(result), starts, ends, tracks, pivots};
    }

    function compileShader(project) {
        const profile = project.profile.replace(/-/g, '_');
        const data = extractCurveData(project);
        const lines = [
            '// Generated by Aechronis Combat Animation for Blockbench 5+.',
            '// Additive model-pixel / Euler XYZ degree offsets. Edit the .bbmodel source.',
            `// Profile: ${profile}. Reload authoring duration: ${project.reload_ticks} ticks (server owns timing).`,
            `uint aechronis_curve_key_${profile}() { return ${data.key}u; }`,
            '', `vec3 aechronis_authored_pivot_${profile}(int bone) {`
        ];
        for (const [bone, pivot] of Object.entries(data.pivots)) lines.push(`    if (bone == ${bone}) return ${vec(pivot)};`);
        lines.push('    return vec3(8.0, 6.0, 12.0);', '}', '');
        return lines.join('\n');
    }

    function combatPreviewPose(profileName, clipName, timeSeconds, reloadTicks, hideScopedGun = true, startingAim = 0) {
        const config = profileName;
        if (!config) throw new Error('Unknown combat preview profile.');
        const pistol = config.style === 'pistol';
        const actions = {idle: 0, fire: 1, reload: 2, aim_in: 3, aim_out: 4, reload_aim: 5, walk: 0, sprint: 0};
        if (!Object.hasOwn(actions, clipName)) throw new Error('Unknown combat preview clip.');
        const action = actions[clipName];
        const reload = action === 2 || action === 5;
        const duration = (reload ? (reloadTicks ?? config.reload_ticks) :
            ({idle: 25, fire: config.fire_ticks ?? 2, aim_in: 7, aim_out: 7, walk: 25, sprint: 12.5})[clipName]) / 20;
        if (!Number.isFinite(timeSeconds) || !Number.isFinite(duration) || duration <= 0) throw new Error('Invalid combat preview time or duration.');
        const clamp = n => Math.min(1, Math.max(0, n));
        const ramp = (t, start, end) => { const n = clamp((t - start) / (end - start)); return n * n * (3 - 2 * n); };
        const add = (a, b) => a.map((n, i) => n + b[i]);
        const sub = (a, b) => a.map((n, i) => n - b[i]);
        const scale = (a, n) => a.map(v => v * n);
        const mix = (a, b, n) => a.map((v, i) => v + (b[i] - v) * n);
        const rotate = (p, degrees) => {
            const [x, y, z] = degrees.map(v => v * Math.PI / 180);
            const rz = [Math.cos(z) * p[0] - Math.sin(z) * p[1], Math.sin(z) * p[0] + Math.cos(z) * p[1], p[2]];
            const ry = [Math.cos(y) * rz[0] + Math.sin(y) * rz[2], rz[1], -Math.sin(y) * rz[0] + Math.cos(y) * rz[2]];
            return [ry[0], Math.cos(x) * ry[1] - Math.sin(x) * ry[2], Math.sin(x) * ry[1] + Math.cos(x) * ry[2]];
        };
        const transform = (pivot = [0, 0, 0]) => ({position: [0, 0, 0], rotation: [0, 0, 0], pivot});
        const t = clamp(timeSeconds / duration);
        const clock = Math.max(0, timeSeconds) * 20;
        let aim = action === 3 ? ramp(t, 0, 1) : action === 4 ? 1 - ramp(t, 0, 1) :
            action === 5 ? 1 - ramp(t, 0, .15) : action === 1 ? clamp(startingAim) : 0;
        const manualScopedCycle = action === 1 && config.scoped && (config.fire_ticks ?? 2) > 2;
        if (manualScopedCycle) aim *= 1 - ramp(t * config.fire_ticks, 0, 2);
        const body = transform([8, 6, 12]);
        if (action === 1) {
            const recoilAim = config.style === 'launcher' || manualScopedCycle ? 0 : aim;
            const kick = ramp(t, 0, .16) * (1 - ramp(t, .16, 1));
            body.rotation = scale([pistol ? 7 - 5.2 * recoilAim : 3 - 2.35 * recoilAim, 0, -.6 * (1 - recoilAim)], kick);
            body.position = scale([0, .08, .8 - .44 * recoilAim], kick);
        } else if (reload) {
            const inspect = ramp(t, 0, .15) * (1 - ramp(t, .85, 1));
            body.rotation = scale(pistol ? [-8, -12, -24] : [-8, 6, -20], inspect);
            body.position = scale(pistol ? [-2, 4, -3] : [-3.5, 1.7, -1], inspect);
        }
        const moving = clipName === 'walk' || clipName === 'sprint' ? 1 : 0;
        const running = clipName === 'sprint' ? 1 : 0;
        const walkPhase = (clock % 25) * (2 * Math.PI / 25);
        const runPhase = (clock % 12.5) * (2 * Math.PI / 12.5);
        const weight = moving * (1 + (.08 - 1) * aim);
        const walkAngles = [.5 * Math.cos(2 * walkPhase), .55 * Math.sin(walkPhase), 1.1 * Math.sin(walkPhase)];
        const runAngles = [1.2 * Math.cos(2 * runPhase), 1.2 * Math.sin(runPhase), 2 * Math.sin(runPhase)];
        const walkShift = [.25 * Math.sin(walkPhase), .16 * Math.cos(2 * walkPhase), .10 * Math.sin(2 * walkPhase)];
        const runShift = [.40 * Math.sin(runPhase), .40 * Math.cos(2 * runPhase), .16 * Math.cos(runPhase)];
        body.rotation = add(body.rotation, scale(mix(walkAngles, runAngles, running), weight));
        body.position = add(body.position, scale(mix(walkShift, runShift, running), weight));
        body.rotation = add(body.rotation, scale([-12, 14, -18], moving * running * (1 - aim)));
        body.position = add(body.position, scale([.5, -1.6, .6], moving * running * (1 - aim)));
        const hipRoot = config.hipRoot;
        const adsRoot = [-(config.sight[0]-8)/16, -(config.sight[1]-8)/16, hipRoot[2]];
        const view = transform([8, 8, 8]);
        view.position = scale(sub(mix(hipRoot, adsRoot, aim), hipRoot), 16);
        if (hideScopedGun && config.scoped && aim >= .999) view.position = [16000, 16000, 16000];
        const magazine = transform(config.magazine);
        const bolt = transform([8, 8, 8]);
        const boltPull = ramp(t, .69, .77) * (1 - ramp(t, .79, .83));
        if (reload) {
            if (pistol) {
                const withdraw = ramp(t, .16, .34) * (1 - ramp(t, .44, .64));
                magazine.position = scale([-.8, -10, 2], withdraw);
            } else {
                const rock = ramp(t, .14, .23) * (1 - ramp(t, .55, .67));
                const withdraw = ramp(t, .21, .36) * (1 - ramp(t, .44, .59));
                magazine.rotation = [-24 * rock, 0, -5 * withdraw];
                magazine.position = scale([-.7, -8, 2], withdraw);
            }
            bolt.position[2] = (pistol ? 2 : 1.65) * boltPull;
        } else if (action === 1) {
            bolt.position[2] = (pistol ? 2 : 1.45) * ramp(t, 0, .2) * (1 - ramp(t, .2, .65));
        }
        if (reload && config.style === 'belt') {
            const withdraw = ramp(t, .16, .35) * (1 - ramp(t, .55, .75));
            magazine.position = scale([-9, -4, 0], withdraw);
            magazine.rotation = [0, 0, 0];
        }
        if (reload && config.style === 'launcher') {
            const replace = ramp(t, .12, .35) * (1 - ramp(t, .65, .95));
            body.rotation = scale([-25, 0, -10], replace);
            body.position = scale([0, -24, 8], replace);
        }
        const dominantOrigin = config.dominant_hand;
        const supportOrigin = config.support_hand;
        let supportPoint = supportOrigin.slice();
        if (reload) {
            const grip = add(config.magazine, [-.7, -1, 0]);
            const target = add(add(magazine.pivot, rotate(sub(grip, magazine.pivot), magazine.rotation)), magazine.position);
            supportPoint = config.style === 'launcher' ? supportOrigin.slice() :
                mix(supportOrigin, target, ramp(t, .03, .17) * (1-ramp(t, .75, .95)));
        }
        return {duration, time: t, aim, steady: clipName === 'idle', body, view, magazine, bolt,
            dominant_hand: {position: [0, 0, 0], rotation: [0, 0, 0], pivot: dominantOrigin},
            support_hand: {position: sub(supportPoint, supportOrigin), rotation: [0, 0, 0], pivot: supportOrigin}};
    }


    // Default Steve preview skin, Mojang Studios. Reuses the combat module's
    // bundled assets at src/main/resources/gun-skins/default/wide/steve.png.
    const DEFAULT_PREVIEW_SKIN = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAMAAACdt4HsAAAAdVBMVEUAAAAKvLwAzMwmGgokGAgrHg0zJBE/KhW3g2uzeV5SPYn///+qclmbY0mQWT8Af38AaGhVVVWUYD52SzOBUzmPXj5JJRBCHQp3QjVqQDA0JRIoKCg3Nzc/Pz9KSko6MYlBNZtGOqUDenoFiIgElZUApKQAr6/wvakZAAAAAXRSTlMAQObYZgAAAolJREFUeNrt1l1rHucZReFrj/whu5hSCCQtlOTE/f+/Jz4q9Cu0YIhLcFVpVg+FsOCVehi8jmZgWOzZz33DM4CXlum3gH95GgeAzQZVeL4gTm6Cbp4vqFkD8HwBazPY8wWbMq9utu3mNZ5fotVezbzOE3kBEFbaZuc8kb00NTMUbWJp678Xf2GV7RRtx1TDQQ6XBNvsmL2+2vHq1TftmMPIyAWujtN2cl274ua2jpVpZneXEjjo7XW1q53V9ds4ODO5xIuhvGHvfLI3aixauig415uuO2+vl9+cncfsFw25zL650fXn687jqnXuP68/X3+eV3zE7y6u9eB73MlfAcfbTf3yR8CfAX+if8S/H5/EAbAxj5LN48tULvEBOh8V1AageMTXe2YHAOwHbZxrzPkSR3+ffr8TR2JDzE/4Fj8CDgEwDsW+q+9GsR07hhg2CsALBgMo2v5wNxXnQXMeGQVW7gUAyKI2m6KDsJ8Au3++F5RZO+kKNQjQcLLWgjwUjBXLltFgWWMUUlviocBgNoxNGgMjSxiYAA7zgLFo2hgIENiDU8gQCzDOmViGFAsEuBcQSDCothhpJaDRA8E5fHqH2nTbYm5fHLo1V0u3B7DAuheoeScRYabjjjuzs17cHVaTrTXmK78m9swP34d9oK/dfeXSIH2PW/MXwPvxN/bJlxw8zlYAcEyeI6gNgA/O8P8neN8xe1IHP2gTzegjvhUDfuRygmwEs2GE4mkCDIAzm2R4yAuPsIdR9k8AvMc+3L9+2UEjo4WP0FpgP19O0MzCsqxIoMsdDBvYcQyGmO0ZJRoYCKjLJWY0BAhYwGUBCgkh8MRdOKt+ruqMwAB2OcEX94U1TPbYJP0PkyyAI1S6cSIAAAAASUVORK5CYII=';

    // Native skin arm geometry / UVs shared with the combat mesh builder. Positions
    // are shader-local pixels: shoulder Y=0, hand Y=12, sleeve inflation=.25.
    // UVs include the carrier's 1/128-pixel quantization, normalized to 64x64.
    function combatArmFaces(slim = false) {
        const corners = {
            down: [[0, 0, 1], [0, 0, 0], [1, 0, 0], [1, 0, 1]],
            up: [[0, 1, 0], [0, 1, 1], [1, 1, 1], [1, 1, 0]],
            north: [[1, 1, 0], [1, 0, 0], [0, 0, 0], [0, 1, 0]],
            south: [[0, 1, 1], [0, 0, 1], [1, 0, 1], [1, 1, 1]],
            west: [[0, 1, 0], [0, 0, 0], [0, 0, 1], [0, 1, 1]],
            east: [[1, 1, 1], [1, 0, 1], [1, 0, 0], [1, 1, 0]]
        };
        const normals = {down: [0, -1, 0], up: [0, 1, 0], north: [0, 0, -1],
            south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0]};
        const base = [[-2, 0, -2], [2, 12, 2]];
        const width = slim ? 3 : 4;
        const faces = [];
        for (const role of ['dominant_hand', 'support_hand']) {
            for (const outer of [false, true]) {
                const expansion = outer ? .25 : 0;
                const origin = role === 'support_hand' ? (outer ? [48, 48] : [32, 48]) :
                    (outer ? [40, 32] : [40, 16]);
                for (const [direction, faceCorners] of Object.entries(corners)) {
                    const normal = normals[direction];
                    const positions = faceCorners.map(corner => corner.map((end, axis) => {
                        let value = base[end][axis] + (end ? expansion : -expansion);
                        if (slim && axis === 0) value -= Math.sign(value) * .5;
                        return value;
                    }));
                    const uvs = faceCorners.map(corner => {
                        let [x, y, z] = corner.map((end, axis) => base[end][axis]);
                        x = Math.min(width - .02, Math.max(.02, (x + 2) / 4 * width));
                        y = Math.min(11.98, Math.max(.02, y));
                        z = Math.min(3.98, Math.max(.02, z + 2));
                        let u, v;
                        if (normal[1] < -.5) [u, v] = [4 + x, 4 - z];
                        else if (normal[1] > .5) [u, v] = [4 + width + x, 4 - z];
                        else if (normal[0] < -.5) [u, v] = [4 - z, 4 + y];
                        else if (normal[0] > .5) [u, v] = [4 + width + z, 4 + y];
                        else if (normal[2] < -.5) [u, v] = [4 + x, 4 + y];
                        else [u, v] = [8 + 2 * width - x, 4 + y];
                        return [u + origin[0], v + origin[1]].map(value => Math.round(value * 128) / (128 * 64));
                    });
                    faces.push({role, outer, positions, normal: normal.slice(), uvs});
                }
            }
        }
        return faces;
    }

    // Carrier muzzle geometry, anchored and quantized exactly as the baker.
    // Colors are linear-sRGB for THREE vertex colors; the palette is sampled
    // with Minecraft's nearest filtering at x=0,5,18,31 respectively.
    function combatMuzzleGeometry(profileName) {
        const profile = profileName;
        const rawAnchor = profile?.muzzle;
        if (!rawAnchor) throw new Error('Unknown combat preview profile.');
        const quantize = value => (Math.round(value * 256 + 32768) - 32768) / 256;
        const anchor = rawAnchor.map(quantize);
        const rings = [[0, .11, .11], [-1.2, 3, .55], [-6, .18, .18], [-20, .006, .006]];
        const colors = [[255, 250, 206], [255, 240, 167], [255, 188, 65], [241, 111, 22]]
            .map(color => color.map(byte => {
                const value = byte / 255;
                return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
            }));
        const points = rings.map(([z, rx, ry]) => [[1, 0], [0, 1], [-1, 0], [0, -1]]
            .map(([x, y]) => [x * rx, y * ry, z]
                .map((offset, axis) => quantize(rawAnchor[axis] + offset) - anchor[axis])));
        const faces = [{positions: points[0].map(point => point.slice()), colors: points[0].map(() => colors[0].slice())}];
        for (let ring = 0; ring < rings.length - 1; ring++) {
            for (let side = 0; side < 4; side++) {
                const next = (side + 1) % 4;
                faces.push({positions: [points[ring][side], points[ring + 1][side], points[ring + 1][next], points[ring][next]].map(point => point.slice()),
                    colors: [colors[ring], colors[ring + 1], colors[ring + 1], colors[ring]].map(color => color.slice())});
            }
        }
        faces.push({positions: [...points[3]].reverse().map(point => point.slice()), colors: points[3].map(() => colors[3].slice())});
        return {anchor, faces};
    }

    // The accepted-shot clock drives recoil, the opaque shrinking muzzle flash,
    // and the translucent trail. One tick is 50ms; the fire clip is two ticks.
    function combatFireState(timeSeconds) {
        if (!Number.isFinite(timeSeconds)) throw new Error('Invalid combat preview time.');
        const elapsed = Math.max(0, timeSeconds) * 20;
        const ramp = (start, end) => {
            const value = Math.min(1, Math.max(0, (elapsed - start) / (end - start)));
            return value * value * (3 - 2 * value);
        };
        const pulse = ramp(0, .12) * (1 - ramp(.3, 1.8));
        const scale = [Math.sqrt(pulse), Math.sqrt(pulse), pulse * (.4 + .6 * ramp(0, .6))];
        const fade = 1 - ramp(1.8, 2.016);
        return {elapsed, pulse, scale, fade, visible: elapsed < 2 && pulse > .0001,
            trailVisible: elapsed < 2.016 && fade > .001, travel: elapsed / 1.8};
    }

    // Store the sampled reference alongside the native keys. Export and preview
    // subtract this same curve, so interpolation never doubles procedural motion.
    function bakeTimeline(model) {
        const config = model.aechronis_combat;
        for (const animation of model.animations || []) {
            if (Object.keys(animation.aechronis_combat_reference || {}).length) continue;
            const clip = animation.aechronis_combat_clip;
            if (!Object.hasOwn(CLIPS, clip)) continue;
            const reference = {};
            for (const group of model.groups) {
                const role = group.aechronis_combat_role;
                if (!Object.hasOwn(ROLES, role)) continue;
                const keys = [];
                for (const channel of role.endsWith('_hand') ? ['position'] : ['position', 'rotation']) {
                    const samples = Array.from({length: 49}, (_, index) => {
                        const time = index === 48 ? animation.length : animation.length * index / 48;
                        const pose = combatPreviewPose(profileFor(config), clip, time, config.reload_ticks, false);
                        // Show camera placement on Gun's curves as well as its
                        // local motion. AWP's hidden scope mesh is not a motion.
                        const value = pose[role][channel].map((v, axis) => v +
                            (role === 'body' ? pose.view[channel][axis] : 0));
                        return {channel, time, interpolation: 'linear', data_points: [{x: value[0], y: value[1], z: value[2]}]};
                    });
                    const constant = samples.every(key => same(Object.values(key.data_points[0]), Object.values(samples[0].data_points[0])));
                    keys.push(...(constant ? [samples[0], samples.at(-1)] : samples));
                }
                reference[role] = keys;
                animation.animators[group.uuid] = {name: group.name, type: 'bone', keyframes: JSON.parse(JSON.stringify(keys))};
            }
            animation.aechronis_combat_reference = reference;
        }
        return model;
    }

    // Also used by the Node validation harness; no Blockbench globals are touched here.
    if (typeof Plugin === 'undefined') {
        if (typeof module !== 'undefined') module.exports = {extractProject, extractCurveData, canonicalCurveData, curveDataKey, compileShader, bakeTimeline, sampleTrack, profileFor, combatPreviewPose, combatArmFaces, combatMuzzleGeometry, combatFireState, CLIPS, ROLES};
        return;
    }

    const disposables = [];
    let format;
    let previewToggle;
    let firstPersonToggle;
    let firstPerson;
    const previewLayers = new Map();
    const previewBones = new Set();
    const active = () => typeof Format !== 'undefined' && Format?.id === ID;
    const escape = value => String(value).replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
    function message(title, text) {
        Blockbench.showMessageBox({title, message: text});
    }
    function guarded(fn) {
        try { return fn(); } catch (error) { message('Combat animation', error.message); }
    }
    function accessoryGeometry(faces) {
        const positions = [], uvs = [], colors = [], indices = [];
        for (const face of faces) {
            const start = positions.length / 3;
            positions.push(...face.positions.flat());
            if (face.uvs) uvs.push(...face.uvs.flatMap(([u, v]) => [u, 1 - v]));
            if (face.colors) colors.push(...face.colors.flat());
            indices.push(start, start + 1, start + 2, start, start + 2, start + 3);
        }
        const geometry = new THREE.BufferGeometry();
        geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
        if (uvs.length) geometry.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
        if (colors.length) geometry.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
        geometry.setIndex(indices);
        geometry.computeVertexNormals();
        return geometry;
    }
    function disposeFirstPersonAccessories(state) {
        const accessories = state?.accessories;
        if (!accessories) return;
        state.accessories = null;
        accessories.root.removeFromParent();
        const materials = new Set();
        accessories.root.traverse(object => {
            object.geometry?.dispose();
            if (object.material) materials.add(object.material);
        });
        materials.forEach(material => material.dispose());
        accessories.texture.dispose();
    }
    function createFirstPersonAccessories(state) {
        const root = new THREE.Group();
        root.name = 'aechronis_combat_accessories';
        root.no_export = true;
        Canvas.scene.add(root);
        const add = (geometry, material, role) => {
            const mesh = new THREE.Mesh(geometry, material);
            mesh.name = `aechronis_combat_${role}`;
            mesh.userData.aechronisCombatAccessory = role;
            mesh.layers.set(state.layer);
            mesh.no_export = true;
            mesh.raycast = () => {}; // Scene helpers never intercept editor selection.
            root.add(mesh);
            return mesh;
        };
        const skinImage = new Image();
        const texture = new THREE.Texture(skinImage);
        texture.magFilter = texture.minFilter = THREE.NearestFilter;
        texture.generateMipmaps = false;
        const skinMaterial = new THREE.MeshLambertMaterial({map: texture, alphaTest: .1});
        const faces = combatArmFaces();
        const arms = [];
        for (const role of ['dominant_hand', 'support_hand']) {
            for (const sleeve of [false, true]) {
                const arm = add(accessoryGeometry(faces.filter(face => face.role === role && face.outer === sleeve)), skinMaterial, role);
                arm.userData.sleeve = sleeve;
                arm.matrixAutoUpdate = false;
                arms.push(arm);
            }
        }
        const muzzle = combatMuzzleGeometry(profileFor(currentConfig()));
        // Blockbench 5's THREE renderer normally outputs LinearEncoding, like
        // the game's raw palette. Match that without changing the editor's
        // renderer; renderers with sRGB output expect the helper's linear RGB.
        const renderer = state.preview.renderer;
        if ((THREE.LinearEncoding !== undefined && renderer.outputEncoding === THREE.LinearEncoding) ||
            (THREE.LinearSRGBColorSpace !== undefined && renderer.outputColorSpace === THREE.LinearSRGBColorSpace)) {
            for (const face of muzzle.faces) face.colors = face.colors.map(color => color.map(value =>
                value <= .0031308 ? value * 12.92 : 1.055 * Math.pow(value, 1 / 2.4) - .055));
        }
        const flash = add(accessoryGeometry(muzzle.faces), new THREE.MeshBasicMaterial({vertexColors: true, toneMapped: false}), 'muzzle_flash');
        const tracer = add(new THREE.BufferGeometry(), new THREE.ShaderMaterial({
            transparent: true, depthWrite: false, side: THREE.DoubleSide, toneMapped: false,
            uniforms: {
                start: {value: new THREE.Vector3()}, end: {value: new THREE.Vector3()},
                screen: {value: new THREE.Vector2(1, 1)}, elapsed: {value: 0}, fade: {value: 0}
            },
            // gun_trail.glsl: screen-linear head progress, clamped pixel width.
            // The target substitutes for the server ray hit, at the crosshair.
            vertexShader: `
                uniform vec3 start, end;
                uniform vec2 screen;
                varying vec3 trailCoordinates;
                void main() {
                    vec4 a = projectionMatrix * viewMatrix * vec4(start, 1.0);
                    vec4 b = projectionMatrix * viewMatrix * vec4(end, 1.0);
                    vec2 direction = (b.xy / b.w - a.xy / a.w) * screen;
                    float span = length(direction);
                    vec2 side = span > .001 ? vec2(direction.y, -direction.x) / span : vec2(1.0, 0.0);
                    gl_Position = mix(a, b, position.x);
                    float halfWidth = clamp(.020 * abs(projectionMatrix[1][1]) * screen.y * 16.0 / gl_Position.w, 1.6, 4.2);
                    gl_Position.xy += side * position.y * halfWidth * (2.0 / screen) * gl_Position.w;
                    // Cancel perspective interpolation (WebGL has no noperspective).
                    trailCoordinates = vec3(position.xy, 1.0) * gl_Position.w;
                }`,
            // Same traveling head, edge softness and color as particle.fsh.
            fragmentShader: `
                uniform float elapsed, fade;
                varying vec3 trailCoordinates;
                void main() {
                    vec2 trailUV = trailCoordinates.xy / trailCoordinates.z;
                    float along = clamp(trailUV.x, 0.0, 1.0);
                    float travel = elapsed / 1.8;
                    float headDistance = (along - travel) / .060;
                    if (abs(headDistance) >= 2.0) discard;
                    float head = exp(-headDistance * headDistance) * (1.0 - smoothstep(1.0, 2.0, abs(headDistance)));
                    float across = abs(trailUV.y);
                    float core = exp(-7.0 * across * across);
                    float softness = exp(-3.0 * across * across) * (1.0 - smoothstep(.65, 1.0, across));
                    if (across >= 1.0 || fade <= .001) discard;
                    vec3 color = mix(vec3(1.0, .68, .25), vec3(1.0, 1.0, .94), core * (.65 + .35 * head));
                    gl_FragColor = vec4(color, min(1.0, (1.05 * core + .40 * softness) * head * fade));
                }`
        }), 'tracer');
        tracer.geometry.setAttribute('position', new THREE.Float32BufferAttribute([0, -1, 0, 0, 1, 0, 1, 1, 0, 1, -1, 0], 3));
        tracer.geometry.setIndex([0, 1, 2, 0, 2, 3]);
        tracer.frustumCulled = false; // Its vertices are positioned by the shader.
        state.accessories = {root, arms, texture, flash, tracer, anchor: new THREE.Vector3().fromArray(muzzle.anchor)};
        skinImage.onload = () => {
            if (state.accessories?.texture !== texture) return;
            texture.needsUpdate = true;
            updateFirstPersonAccessories();
        };
        skinImage.src = DEFAULT_PREVIEW_SKIN;
    }
    function updateFirstPersonAccessories() {
        const state = firstPerson;
        if (!state || !active() || !Modes.animate || Project !== state.project) return;
        if (!state.accessories) createFirstPersonAccessories(state);
        const {root, arms, texture, flash, tracer, anchor} = state.accessories;
        const groups = Object.fromEntries(Group.all.map(group => [group.aechronis_combat_role, group]));
        root.visible = ['body', 'dominant_hand', 'support_hand'].every(role => groups[role]?.mesh);
        if (!root.visible) return;
        Canvas.scene.updateMatrixWorld(true);
        // Shader arms rotate about the hand end, toward a fixed camera-side
        // shoulder guide. Parenting them to the gun would also rotate shoulders.
        for (const arm of arms) {
            const role = arm.userData.aechronisCombatAccessory;
            const grip = groups[role].mesh.getWorldPosition(new THREE.Vector3());
            const guide = new THREE.Vector3(...(role === 'support_hand' ? [-.35, -1.30, -.05] : [1.05, -.85, -.06]))
                .multiplyScalar(16).add(new THREE.Vector3().fromArray(state.eye));
            const along = grip.clone().sub(guide).normalize();
            if (along.lengthSq() < .0001) along.set(0, 1, 0);
            const across = new THREE.Vector3().crossVectors(along, new THREE.Vector3(0, 0, 1));
            if (across.lengthSq() < .0001) across.set(1, 0, 0);
            across.normalize();
            const depth = new THREE.Vector3().crossVectors(across, along);
            arm.matrix.makeBasis(across, along, depth).setPosition(grip.addScaledVector(along, -12));
            arm.matrixWorldNeedsUpdate = true;
            arm.visible = texture.image.complete && texture.image.naturalWidth === 64;
        }
        const body = groups.body.mesh;
        const muzzle = body.localToWorld(anchor.clone().sub(new THREE.Vector3().fromArray(groups.body.origin)));
        flash.position.copy(muzzle);
        body.getWorldQuaternion(flash.quaternion);
        const animation = Animation.selected;
        const firing = previewToggle.value && animation?.playing &&
            animation.aechronis_combat_clip === 'fire' && Timeline.time <= animation.length;
        const effect = combatFireState(firing ? animation.time : .1);
        flash.visible = firing && effect.visible;
        flash.scale.fromArray(effect.scale);
        tracer.visible = firing && effect.trailVisible;
        const uniforms = tracer.material.uniforms;
        uniforms.start.value.copy(muzzle);
        uniforms.end.value.fromArray(state.eye).add(new THREE.Vector3(0, 0, -16 * 32));
        uniforms.screen.value.set(Math.max(1, state.preview.canvas.width), Math.max(1, state.preview.canvas.height));
        uniforms.elapsed.value = effect.elapsed;
        uniforms.fade.value = effect.fade;
    }
    function leaveFirstPerson() {
        const state = firstPerson;
        firstPerson = null;
        if (state) {
            const {preview} = state;
            disposeFirstPersonAccessories(state);
            for (const [object, mask] of state.masks) object.layers.mask = mask;
            state.badge.remove();
            if (preview.orbit_gizmo) preview.orbit_gizmo.node.style.display = state.gizmoDisplay;
            // These setters alter cameras/locks, so restore their snapshots after.
            preview.setProjectionMode(state.isOrtho);
            preview.side_view_target.copy(state.sideTarget);
            preview.setLockedAngle(state.angle);
            preview.camPers.copy(state.perspective, false);
            preview.camOrtho.copy(state.orthographic, false);
            preview.camOrtho.axis = state.orthoAxis;
            preview.camOrtho.backgroundHandle = state.backgroundHandle;
            preview.aspect_ratio = state.aspectRatio;
            preview.label.textContent = state.label;
            preview.controls.target.copy(state.target);
            Object.assign(preview.controls, state.controls);
            preview.resize();
            preview.camera.updateMatrixWorld();
        }
        firstPersonToggle?.set(false);
    }
    function firstPersonModeChanged({mode} = {}) {
        if (mode?.id === 'animate') leaveFirstPerson();
    }
    function firstPersonProjectChanged({project} = {}) {
        if (firstPerson && (!project || project === firstPerson.project)) leaveFirstPerson();
    }
    function saveFirstPersonEditorState({project}) {
        if (firstPerson?.project !== project) return;
        const state = firstPerson;
        // Blockbench saves the view BEFORE unselect_project. Keep the regular
        // editor camera in that state so returning to the project restores it.
        project.previews[state.preview.id] = {
            position: (state.isOrtho ? state.orthographic : state.perspective).position.toArray(),
            target: state.target.toArray(), orthographic: state.isOrtho,
            zoom: state.orthographic.zoom, angle: state.angle
        };
    }
    function refreshFirstPerson() {
        const state = firstPerson;
        if (!state) return;
        if (!active() || !Modes.animate || Project !== state.project) return leaveFirstPerson();
        const {preview, layer, masks} = state;
        const include = object => {
            if (!object) return;
            if (!masks.has(object)) masks.set(object, object.layers.mask);
            object.layers.enable(layer);
        };
        for (const element of Outliner.elements) {
            // Add only actual gun surfaces. Child outlines, hand target helpers,
            // the grid and transform gizmos remain on their original layers.
            if (element.export !== false && !element.parent?.aechronis_combat_role?.endsWith('_hand')) include(element.mesh);
        }
        Canvas.scene.traverse(object => { if (object.isLight) include(object); });
        if (preview.isOrtho) preview.setProjectionMode(false);
        preview.controls.enabled = false;
        preview.controls.unlinked = true;
        const camera = preview.camPers;
        camera.position.fromArray(state.eye);
        camera.up.set(0, 1, 0);
        camera.quaternion.set(0, 0, 0, 1); // Minecraft camera space looks down -Z.
        preview.controls.target.copy(camera.position).add(new THREE.Vector3(0, 0, -16));
        camera.layers.set(layer);
        if (camera.fov !== 70 || camera.near !== .8 || camera.far !== 1600 || camera.zoom !== 1) {
            // Camera.calculateHudFov / GameRenderer hand projection in 26.2:
            // 70 degrees, .05..100 blocks, expressed here in 16px model units.
            camera.near = .8;
            camera.far = 1600;
            camera.zoom = 1;
            preview.setFOV(70);
        }
        camera.updateMatrixWorld();
        updateFirstPersonAccessories();
    }
    function enterFirstPerson() {
        if (firstPerson || !active() || !Modes.animate) return;
        const profile = profileFor(currentConfig());
        const preview = Preview.selected;
        if (!profile || !preview) return firstPersonToggle.set(false);
        let usedLayers = 0;
        Canvas.scene.traverse(object => { usedLayers |= object.layers.mask; });
        for (const view of Preview.all) usedLayers |= view.camPers.layers.mask | view.camOrtho.layers.mask;
        const layer = Array.from({length: 25}, (_, index) => index + 7).find(index => !(usedLayers & (1 << index)));
        if (layer === undefined) {
            firstPersonToggle.set(false);
            return message('First-person view', 'No unused viewport layer is available.');
        }
        const badge = document.createElement('div');
        badge.className = 'aechronis-first-person-label';
        badge.textContent = 'First person · Right hand · 70°';
        badge.style.cssText = 'position:absolute;bottom:10px;left:10px;padding:4px 8px;background:#181a20cc;color:#ddd;border-radius:4px;font-size:12px;pointer-events:none;z-index:2';
        firstPerson = {
            project: Project, preview, layer, masks: new Map(), badge,
            // The rig preview is 8 + 16*(viewPoint - hipRoot).
            eye: profile.hipRoot.map(value => 8 - 16 * value),
            perspective: preview.camPers.clone(), orthographic: preview.camOrtho.clone(),
            isOrtho: preview.isOrtho, angle: preview.angle, orthoAxis: preview.camOrtho.axis,
            backgroundHandle: structuredClone(preview.camOrtho.backgroundHandle),
            sideTarget: preview.side_view_target.clone(), aspectRatio: preview.aspect_ratio,
            target: preview.controls.target.clone(), label: preview.label.textContent,
            gizmoDisplay: preview.orbit_gizmo?.node.style.display || '',
            controls: Object.fromEntries(['enabled', 'unlinked', 'enableRotate', 'enablePan', 'enableZoom'].map(key => [key, preview.controls[key]]))
        };
        preview.node.appendChild(badge);
        if (preview.orbit_gizmo) preview.orbit_gizmo.node.style.display = 'none';
        preview.setProjectionMode(false);
        previewToggle.set(true);
        refreshFirstPerson();
        Animator.preview();
    }
    function clearPreview() {
        for (const [node, {layer, inside}] of [...previewLayers].reverse()) {
            if (inside) {
                for (const child of [...layer.children]) node.add(child);
            } else if (layer.parent && node.parent === layer) {
                layer.parent.add(node);
            }
            layer.removeFromParent();
        }
        previewLayers.clear();
        for (const mesh of previewBones) {
            if (mesh.fix_position) mesh.position.copy(mesh.fix_position);
            if (mesh.fix_rotation) mesh.rotation.copy(mesh.fix_rotation);
        }
        previewBones.clear();
    }
    function previewLayer(node, inside) {
        let entry = previewLayers.get(node);
        if (!entry) {
            const layer = new THREE.Group();
            layer.name = 'aechronis_combat_preview';
            layer.userData.aechronisCombatPreview = true;
            entry = {layer, inside};
            previewLayers.set(node, entry);
        }
        const {layer} = entry;
        if (inside) {
            // These helpers belong only to the render scene, never the outliner.
            // Keep the procedural part BELOW its authored bone: authored * base.
            for (const child of [...node.children]) if (child !== layer) layer.add(child);
            if (layer.parent !== node) node.add(layer);
        } else if (node.parent !== layer && node.parent) {
            node.parent.add(layer);
            layer.add(node);
        }
        return layer;
    }
    function setPreviewTransform(layer, transform, origin = [0, 0, 0]) {
        layer.rotation.set(...transform.rotation.map(value => value * Math.PI / 180), 'XYZ');
        // Express pivot + R*(p-pivot) + shift in this bone's local coordinates.
        const pivot = new THREE.Vector3().fromArray(transform.pivot).sub(new THREE.Vector3().fromArray(origin));
        layer.position.copy(pivot).sub(pivot.clone().applyQuaternion(layer.quaternion))
            .add(new THREE.Vector3().fromArray(transform.position));
    }
    function displayCombatPreview({reduced_updates} = {}) {
        if (!reduced_updates || !active() || !Modes.animate || !previewToggle?.value) {
            if (previewLayers.size) clearPreview();
            return;
        }
        const animation = Animation.selected;
        const config = currentConfig();
        const clip = animation?.aechronis_combat_clip;
        if (!animation?.playing || !Object.hasOwn(CLIPS, clip) || !config?.profile_settings ||
            (animation.loop === 'once' && Timeline.time > animation.length)) {
            clearPreview();
            return;
        }
        const groups = Object.fromEntries(Group.all.map(group => [group.aechronis_combat_role, group]));
        if (Object.keys(ROLES).some(role => !groups[role]?.mesh?.fix_position)) {
            clearPreview();
            return;
        }
        const pose = combatPreviewPose(profileFor(config), clip, animation.time, config.reload_ticks);
        const body = groups.body.mesh;
        setPreviewTransform(previewLayer(body, false), pose.view);
        // This event runs after the native rest reset and BEFORE authored keys.
        // Blockbench captures pre_rotation here, keeping gizmo edits as offsets.
        body.rotation.x += pose.body.rotation[0] * Math.PI / 180;
        body.rotation.y += pose.body.rotation[1] * Math.PI / 180;
        body.rotation.z += pose.body.rotation[2] * Math.PI / 180;
        body.position.add(new THREE.Vector3().fromArray(pose.body.position));
        previewBones.add(body);
        for (const role of ['magazine', 'bolt']) {
            const mesh = groups[role].mesh;
            // Native keys and reference subtraction operate on Euler channels;
            // adding the procedural pose here matches the single runtime pivot.
            mesh.rotation.x += pose[role].rotation[0] * Math.PI / 180;
            mesh.rotation.y += pose[role].rotation[1] * Math.PI / 180;
            mesh.rotation.z += pose[role].rotation[2] * Math.PI / 180;
            mesh.position.add(new THREE.Vector3().fromArray(pose[role].position));
            previewBones.add(mesh);
        }
        for (const role of ['dominant_hand', 'support_hand']) {
            const mesh = groups[role].mesh;
            mesh.position.add(new THREE.Vector3().fromArray(pose[role].position));
            previewBones.add(mesh);
        }
        for (const [role, keys] of Object.entries(animation.aechronis_combat_reference || {})) {
            const group = groups[role];
            if (!group) continue;
            const animator = animation.animators[group.uuid];
            for (const channel of ['position', 'rotation']) {
                if (animator?.muted[channel]) continue;
                const value = sampleTrack(readTrack(keys, channel, animation.length, 'Timeline reference'), animation.time / animation.length,
                    1 / (1200 * animation.length));
                const target = channel === 'position' ? group.mesh.position : group.mesh.rotation;
                ['x', 'y', 'z'].forEach((axis, index) => { target[axis] -= value[index] * (channel === 'rotation' ? Math.PI / 180 : 1); });
            }
            previewBones.add(group.mesh);
        }
    }
    function showDialog(dialog) {
        for (let i = disposables.length - 1; i >= 0; i--) {
            const previous = disposables[i];
            if (previous instanceof Dialog && !Dialog.stack.includes(previous)) {
                previous.delete();
                disposables.splice(i, 1);
            }
        }
        disposables.push(dialog);
        dialog.show();
    }
    function snapshot(bitmaps = false) {
        // Muting is editor state and is omitted from the normal bbmodel codec.
        const model = Codecs.project.compile({raw: true, bitmaps});
        for (const animation of Animation.all) {
            const saved = model.animations?.find(item => item.uuid === animation.uuid);
            if (!saved) continue;
            for (const [uuid, animator] of Object.entries(animation.animators)) {
                if (saved.animators?.[uuid]) saved.animators[uuid].muted = {...animator.muted};
            }
        }
        return model;
    }
    // Desktop build settings stay local to this editor, never inside shared models.
    let buildSettings = {};
    try { buildSettings = JSON.parse(localStorage.getItem(`${ID}_build`) || '{}'); } catch (_) {}
    let building = false;
    const desktop = () => typeof isApp !== 'undefined' && isApp;
    const markerNames = {magazine: 'Magazine pivot', bolt: 'Bolt pivot', dominant_hand: 'Dominant grip',
        support_hand: 'Support grip', sight: 'Sight line', muzzle: 'Muzzle', hipRoot: 'Hip placement'};
    const centerOf = cube => cube.from.map((value, axis) => (value + cube.to[axis]) / 2);

    function currentConfig() {
        const config = JSON.parse(JSON.stringify(Project.aechronis_combat));
        const markers = Cube.all.filter(cube => cube.aechronis_combat_marker);
        if (!markers.length) return config;
        config.rig ||= {};
        for (const marker of markers) {
            const role = marker.aechronis_combat_marker;
            if (!Object.hasOwn(markerNames, role)) continue;
            const point = centerOf(marker);
            if (role === 'bolt') continue; // Its pivot lives on the bone.
            config.rig[role] = role === 'sight' ? point.slice(0, 2) :
                role === 'hipRoot' ? point.map(value => (value - 8) / 16) : point;
        }
        return config;
    }
    function compileCombatProject({model}) {
        if (!active()) return;
        const seen = new Set();
        for (const marker of Cube.all.filter(cube => cube.aechronis_combat_marker)) {
            const role = marker.aechronis_combat_marker;
            if (!Object.hasOwn(markerNames, role) || seen.has(role)) fail('Keep exactly one setup marker of each kind.');
            if (!same(marker.rotation, zero)) fail(`${marker.name}: move setup markers without rotating them.`);
            seen.add(role);
        }
        model.aechronis_combat = currentConfig();
        for (const marker of Cube.all.filter(cube => cube.aechronis_combat_marker)) {
            const group = model.groups.find(group => group.aechronis_combat_role === marker.aechronis_combat_marker);
            if (group) group.origin = centerOf(marker);
        }
    }
    function syncRigMarkers() {
        if (!active()) return;
        let changed = false;
        for (const marker of Cube.all.filter(cube => cube.aechronis_combat_marker)) {
            const group = Group.all.find(group => group.aechronis_combat_role === marker.aechronis_combat_marker);
            if (group && !same(group.origin, centerOf(marker))) {
                group.origin.V3_set(centerOf(marker));
                changed = true;
            }
        }
        if (changed) Canvas.updateAllBones();
        if (firstPerson) {
            // Recreate effect attachment and camera placement from the edited rig.
            disposeFirstPersonAccessories(firstPerson);
            firstPerson.eye = profileFor(currentConfig()).hipRoot.map(value => 8 - 16 * value);
        }
    }
    function ensureRigMarkers() {
        const config = currentConfig(), settings = profileFor(config);
        const body = Group.all.find(group => group.aechronis_combat_role === 'body');
        const created = [];
        for (const [role, name] of Object.entries(markerNames)) {
            if (Cube.all.some(cube => cube.aechronis_combat_marker === role)) continue;
            const group = Group.all.find(group => group.aechronis_combat_role === role);
            const point = role === 'hipRoot' ? settings.hipRoot.map(value => 8 + 16 * value) :
                role === 'sight' ? [...settings.sight, 8] : group ? group.origin.slice() : settings[role];
            const marker = new Cube({name: `${name} (setup marker)`, aechronis_combat_marker: role,
                from: point.map(value => value - .2), to: point.map(value => value + .2), origin: point,
                rotation: [0, 0, 0], box_uv: false, color: Object.keys(markerNames).indexOf(role) % 8,
                export: false, faces: Object.fromEntries(['north', 'south', 'east', 'west', 'up', 'down']
                    .map(side => [side, {texture: false, uv: [0, 0, 1, 1]}]))}).addTo(body).init();
            created.push(marker);
        }
        return created;
    }
    function rigSetup() {
        guarded(() => {
            Modes.options.edit.select();
            Undo.initEdit({elements: [], outliner: true});
            const created = ensureRigMarkers();
            if (created.length) Undo.finishEdit('Add combat setup markers', {elements: created});
            else Undo.cancelEdit();
            const settings = profileFor(currentConfig());
            const form = {};
            for (const [role, label] of Object.entries(markerNames)) {
                const marker = Cube.all.find(cube => cube.aechronis_combat_marker === role);
                form[role] = {label, type: 'vector', dimensions: role === 'sight' ? 2 : 3,
                    value: role === 'hipRoot' ? settings.hipRoot : role === 'sight' ? settings.sight : centerOf(marker), step: .05};
            }
            showDialog(new Dialog(`${ID}_rig`, {
                title: 'Rig setup', form,
                lines: ['<p>Coordinates are in model pixels; hip placement is in blocks. Barrel direction: −Z.</p>'],
                onConfirm(values) {
                    guarded(() => {
                        const markers = Cube.all.filter(cube => cube.aechronis_combat_marker);
                        Undo.initEdit({elements: markers, groups: Group.all});
                        for (const marker of markers) {
                            const role = marker.aechronis_combat_marker;
                            const point = role === 'hipRoot' ? values[role].map(value => 8 + 16 * value) :
                                role === 'sight' ? [...values[role], centerOf(marker)[2]] : values[role];
                            marker.from.V3_set(point.map(value => value - .2));
                            marker.to.V3_set(point.map(value => value + .2));
                            marker.origin.V3_set(point);
                        }
                        syncRigMarkers();
                        Undo.finishEdit('Place combat rig markers');
                        Canvas.updateAll();
                    });
                }
            }));
        });
    }
    function assignParts(role) {
        guarded(() => {
            Modes.options.edit.select();
            const selected = Outliner.selected.filter(element => (element instanceof Mesh || element instanceof Cube) && element.export !== false);
            if (!selected.length) fail('Select this part’s meshes or cubes in the outliner.');
            const group = Group.all.find(group => group.aechronis_combat_role === role);
            Undo.initEdit({elements: selected, outliner: true});
            for (const element of selected) element.addTo(group);
            Undo.finishEdit(`Assign combat ${role}`);
            Canvas.updateAll();
            Blockbench.showQuickMessage(`${selected.length} parts assigned to ${group.name}`);
        });
    }
    function configureBuild(continueWith) {
        if (!desktop()) return message('Combat asset build', 'Import and build require desktop Blockbench.');
        showDialog(new Dialog(`${ID}_build_settings`, {
            title: 'Combat build settings',
            form: {root: {label: 'Aechronis repository', type: 'folder', value: buildSettings.root || ''}},
            lines: ['<p>Choose your Aechronis checkout. Builds write assets there.</p>'],
            onConfirm(values) {
                guarded(() => {
                    const fs = require('fs'), path = require('path');
                    if (!fs.existsSync(path.join(values.root, 'modules/combat/tools/blockbench/build-project.cjs'))) fail('Choose the Aechronis checkout containing build-project.cjs.');
                    buildSettings = values;
                    localStorage.setItem(`${ID}_build`, JSON.stringify(values));
                    if (typeof continueWith === 'function') continueWith();
                });
            }
        }));
    }
    function bridge(request) {
        return new Promise((resolve, reject) => {
            // Blockbench's scoped plugin API permits process/child_process but
            // not worker_threads. Electron's own Node runtime needs no PATH entry.
            const path = require('path'), runtime = require('process');
            const child = require('child_process').execFile(runtime.execPath,
                [path.join(buildSettings.root, 'modules/combat/tools/blockbench/build-project.cjs')],
                {cwd: buildSettings.root, env: {...runtime.env, ELECTRON_RUN_AS_NODE: '1'}, maxBuffer: 64 * 1024 * 1024},
                (error, stdout, stderr) => {
                    if (error) return reject(new Error(stderr.trim() || `${error.message}\nCheck the repository in Build settings.`));
                    try { resolve(JSON.parse(stdout)); } catch (_) { reject(new Error('The combat builder returned an invalid response.')); }
                });
            child.stdin.on('error', () => {}); // execFile's callback reports process failures.
            child.stdin.end(JSON.stringify(request));
            // No kill timer: publication must finish its commit/rollback even if
            // the editor closes the project while the background build runs.
        });
    }
    async function showProfiles() {
        if (!desktop() || !buildSettings.root) return configureBuild(showProfiles);
        try {
            const options = await bridge({action: 'options'});
            showDialog(new Dialog(ID + '_profile_iteration', {
                title: 'Edit viewmodel profiles',
                form: {pack: {label: 'Iteration', type: 'select', value: options.packs[0],
                    options: Object.fromEntries(options.packs.map(pack => [pack, pack]))}},
                onConfirm({pack}) {
                    const expected = options.profiles[pack];
                    const entries = Object.entries(expected).sort((a, b) => a[1].id - b[1].id);
                    const form = {};
                    entries.forEach(([gun, profile], index) => {
                        form['delete_' + index] = {label: 'Delete ' + gun, type: 'checkbox', value: false};
                        form['name_' + index] = {label: gun + ' — name', type: 'text', value: gun};
                        form['id_' + index] = {label: 'Profile ID', type: 'select', value: String(profile.id),
                            options: Object.fromEntries(Array.from({length: 16}, (_, id) => [id, String(id)]))};
                    });
                    showDialog(new Dialog(ID + '_profiles', {
                        title: 'Edit viewmodel profiles — ' + pack, width: 640, form,
                        lines: ['<p>' + entries.length + '/16 slots used. Deleting a profile removes its published assets. Update Kotlin references after saving.</p>'],
                        buttons: entries.length ? ['Save', 'Cancel'] : ['Close'],
                        async onConfirm(values) {
                            if (!entries.length) return;
                            try {
                                const profiles = entries.map(([from], index) => ({from,
                                    name: values['name_' + index].trim(), id: Number(values['id_' + index]), delete: values['delete_' + index] === true}));
                                const result = await bridge({action: 'edit_profiles', pack, expected, profiles});
                                message('Viewmodel profiles saved', result.changes.length
                                    ? 'Updated ' + result.files.length + ' files. Update the matching Kotlin references:\n' +
                                        result.changes.map(c => c.from + ' (ID ' + c.oldId + ')' + (c.deleted ? ' → deleted (remove Kotlin registration)' : ' → ' + c.name + ' (ID ' + c.id + ')')).join('\n')
                                    : 'No changes.');
                            } catch (error) { message('Could not edit viewmodel profiles', error.message); }
                        }
                    }));
                }
            }));
        } catch (error) { message('Could not load viewmodel profiles', error.message); }
    }
    async function createNewGun() {
        if (!desktop() || !buildSettings.root) return configureBuild(createNewGun);
        try {
            const options = await bridge({action: 'options'});
            showDialog(new Dialog(`${ID}_new_template`, {
                title: 'Create new gun — template',
                form: {
                    template: {label: 'Animation template', type: 'select', value: 'rifle',
                        options: Object.fromEntries(Object.entries(options.templates).map(([key, t]) => [key, t.label]))}
                },
                onConfirm(choice) {
                    const template = options.templates[choice.template];
                    showDialog(new Dialog(`${ID}_new_gun`, {
                        title: 'Create new gun — settings',
                        lines: ['<p>Next: choose a Java model and its PNG textures.</p>'],
                        form: {
                            name: {label: 'Gun name', type: 'text', value: ''},
                            reloadTime: {label: 'Reload animation duration (ms)', type: 'number', value: template.reloadTime, min: 50, max: 12750, step: 50}
                        },
                        onConfirm(values) { importJavaGun({...choice, ...values}); }
                    }));
                }
            }));
        } catch (error) { message('New gun', error.message); }
    }
    function importJavaGun(newGun) {
        if (!desktop() || !buildSettings.root) return configureBuild(() => importJavaGun(newGun));
        Blockbench.import({type: 'Java gun model', extensions: ['json'], readtype: 'text'}, files => {
            if (!files?.length) return;
            guarded(() => {
                const source = JSON.parse(files[0].content);
                if (!source.elements?.length) fail('Choose a Java model containing elements, not a parent-only item definition.');
                const resolveTexture = (key, seen = new Set()) => {
                    if (seen.has(key)) fail('The model contains a cyclic texture reference.');
                    seen.add(key);
                    const value = source.textures?.[key] || key;
                    return value.startsWith('#') ? resolveTexture(value.slice(1), seen) : value;
                };
                const references = [...new Set(source.elements.flatMap(element => Object.values(element.faces || {})
                    .filter(face => typeof face.texture === 'string').map(face => resolveTexture(face.texture.replace(/^#/, '')))))];
                if (!references.length) fail('The Java model needs explicit texture references.');
                const form = {
                    scale: {label: 'Source scale', type: 'number', value: 1, min: .001, max: 100},
                    offset: {label: 'Source offset', type: 'vector', value: [0, 0, 0]},
                    reverse: {label: 'Source barrel faces +Z', type: 'checkbox', value: false}};
                references.forEach((ref, index) => { form[`texture_${index}`] = {label: `PNG: ${ref}`, type: 'file', extensions: ['png'], readtype: 'image'}; });
                showDialog(new Dialog(`${ID}_import_java`, {
                    title: 'Create new gun — import model', form,
                    lines: ['<p>After import, assign moving parts and position the rig markers.</p>'],
                    async onConfirm(values) {
                        try {
                            const fs = require('fs');
                            const textures = Object.fromEntries(references.map((ref, index) => {
                                const file = values[`texture_${index}`];
                                if (!file) fail(`Choose the PNG for ${ref}.`);
                                return [ref, `data:image/png;base64,${fs.readFileSync(file).toString('base64')}`];
                            }));
                            Blockbench.showQuickMessage('Importing combat geometry…');
                            const {model} = await bridge({action: 'import', model: source, textures,
                                new_gun: newGun,
                                scale: values.scale, offset: values.offset, reverse: values.reverse, auto_assign: false});
                            Codecs.project.load(bakeTimeline(model), {path: `${model.aechronis_combat.profile}-combat.bbmodel`, no_file: true});
                            rigSetup();
                        } catch (error) { message('Combat import failed', error.message); }
                    }
                }));
            });
        });
    }
    async function buildCombatAssets() {
        if (!desktop() || !buildSettings.root) return configureBuild(buildCombatAssets);
        if (building) return message('Combat asset build', 'A build is already running.');
        try {
            const model = snapshot(true);
            extractProject(model);
            const options = await bridge({action: 'options'});
            if (!options.packs.length) fail('No iterations found in this checkout.');
            showDialog(new Dialog(`${ID}_export_iteration`, {
                title: 'Build combat assets — iteration',
                form: {pack: {label: 'Target iteration', type: 'select', value: options.packs[0],
                    options: Object.fromEntries(options.packs.map(pack => [pack, pack]))}},
                onConfirm({pack}) {
                    const occupied = new Map(Object.entries(options.profiles[pack]).map(([gun, p]) => [p.id, gun]));
                    const ids = Array.from({length: 16}, (_, id) => id);
                    showDialog(new Dialog(`${ID}_export_slot`, {
                        title: 'Build combat assets — profile slot',
                        lines: [`<p><b>${escape(pack)}</b>: occupied slots replace the named viewmodel. Free slots use the name below.</p>`],
                        form: {
                            slot: {label: 'Profile ID', type: 'select', value: String(ids.find(id => !occupied.has(id)) ?? 0),
                                options: Object.fromEntries(ids.map(id => [String(id), `${id} — ${occupied.has(id) ? 'Replace ' + occupied.get(id) : 'Free'}`]))},
                            name: {label: 'Gun name for a free slot', type: 'text', value: model.aechronis_combat.profile}
                        },
                        buttons: ['Build / replace', 'Cancel'],
                        onConfirm({slot, name}) {
                            const id = Number(slot);
                            exportCombatAssets(model, {pack, id, name, expected: occupied.get(id) ?? null});
                        }
                    }));
                }
            }));
        } catch (error) { message('Combat build failed', error.message); }
    }
    async function exportCombatAssets(model, target) {
        if (building) return message('Combat asset build', 'A build is already running.');
        building = true;
        try {
            Blockbench.showQuickMessage('Building combat geometry and animations…', 10000);
            const result = await bridge({action: 'build', model, target});
            message('Combat assets built', `${result.faces} faces; ${result.files.length} files updated.\n\nIteration: ${result.target.pack}\nGun: ${result.target.name}\nanimatedViewModelProfile: ${result.target.id}\nItem model: aechronis:${result.target.name}\nreloadTime: ${result.reload_ticks * 50} ms\nfireAnimationTicks: ${result.fire_ticks}\n\n` +
                (result.warnings.length ? result.warnings.join('\n') + '\n\n' : '') +
                'Register new guns in Kotlin. Save your project with File → Save.');
        } catch (error) { message('Combat build failed', error.message); }
        finally { building = false; }
    }

    function openStarter() {
        Blockbench.import({type: 'Combat animation project', extensions: ['bbmodel'], readtype: 'text'}, files => {
            if (!files?.length) return;
            guarded(() => {
                const model = JSON.parse(files[0].content);
                if (model.meta?.model_format !== ID || !model.aechronis_combat) {
                    fail('Choose an Aechronis combat .bbmodel.');
                }
                Codecs.project.load(model, files[0]);
                Modes.options.animate.select();
                const animation = Animation.all.find(animation => animation.aechronis_combat_clip === 'reload');
                animation?.select();
                for (const animator of Object.values(animation?.animators || {})) animator.addToTimeline();
                Group.all.find(group => group.aechronis_combat_role === 'body')?.select();
                Timeline.setTime(0);
                Animator.preview();
            });
        });
    }
    function validate() {
        guarded(() => {
            const project = extractProject(snapshot());
            const trackCount = Object.values(project.clips).reduce((sum, tracks) => sum + tracks.length, 0);
            const dialog = new Dialog('aechronis_animation_export', {
                title: 'Combat animation validation',
                width: 610,
                lines: [
                    `<p><b>${project.profile}</b> · ${Object.keys(project.clips).length} clips · ${trackCount} authored channels</p>`,
                    ...project.warnings.map(warning => `<p>⚠ ${escape(warning)}</p>`)
                ],
                buttons: ['OK']
            });
            showDialog(dialog);
        });
    }
    Plugin.register(ID, {
        title: 'Aechronis Combat Animation',
        author: 'luna',
        description: 'Create and animate Aechronis gun viewmodels.',
        icon: 'animation', min_version: '5.0.0', variant: 'both', await_loading: true,
        tags: ['Minecraft: Java Edition', 'Animation'],
        onload() {
            disposables.push(new Property(ModelProject, 'object', 'aechronis_combat', {default: () => ({}), exposed: false, condition: active}));
            disposables.push(new Property(Group, 'string', 'aechronis_combat_role', {default: '', exposed: false, condition: active}));
            disposables.push(new Property(Animation, 'string', 'aechronis_combat_clip', {default: '', exposed: false, condition: active}));
            disposables.push(new Property(Animation, 'object', 'aechronis_combat_reference', {default: () => ({}), exposed: false, condition: active}));
            disposables.push(new Property(Cube, 'string', 'aechronis_combat_marker', {default: '', exposed: false, condition: active}));
            Codecs.project.on('compile', compileCombatProject);
            for (const event of ['finished_edit', 'undo', 'redo']) Blockbench.on(event, syncRigMarkers);
            format = new ModelFormat(ID, {
                name: 'Aechronis Combat Animation', description: 'First-person shader rigs for the combat module', icon: 'animation',
                category: 'minecraft', target: 'Minecraft Java 26.2',
                bone_rig: true, animation_mode: true, animation_files: false,
                meshes: true, rotate_cubes: true, optional_box_uv: true, box_uv: false,
                single_texture: false, per_texture_uv_size: true, uv_rotation: true, centered_grid: true,
                euler_order: 'XYZ', quaternion_interpolation: false, animation_loop_wrapping: false,
                format_page: {content: [{type: 'text', text: 'Open a combat project, or use Aechronis → Create new gun.'}], button_text: 'Open combat project'}
            });
            format.new = () => { openStarter(); return false; };
            disposables.push(format);
            previewToggle = new Toggle(`${ID}_preview`, {
                name: 'Preview built-in gun motion', icon: 'play_circle', default: true, condition: active,
                onChange() { if (active() && Modes.animate) Animator.preview(); }
            });
            disposables.push(previewToggle);
            firstPersonToggle = new Toggle(`${ID}_first_person`, {
                name: 'First-person view', icon: 'visibility', default: false,
                condition: () => active() && Modes.animate,
                onChange(value) { if (value) enterFirstPerson(); else leaveFirstPerson(); }
            });
            disposables.push(firstPersonToggle);
            Blockbench.on('display_default_pose', displayCombatPreview);
            Blockbench.on('unselect_project', clearPreview);
            Blockbench.on('render_frame', refreshFirstPerson);
            Blockbench.on('display_animation_frame', updateFirstPersonAccessories);
            Blockbench.on('unselect_mode', firstPersonModeChanged);
            Blockbench.on('unselect_project', firstPersonProjectChanged);
            Blockbench.on('close_project', firstPersonProjectChanged);
            Blockbench.on('save_editor_state', saveFirstPersonEditorState);
            const actions = [
                ['create_gun', 'Create new gun…', 'add_box', createNewGun, () => true],
                ['profiles', 'Edit viewmodel profiles…', 'edit', showProfiles, () => true],
                ['rig', 'Rig setup…', 'accessibility', rigSetup, active],
                ['assign_body', 'Assign selected parts → Gun', 'category', () => assignParts('body'), active],
                ['assign_magazine', 'Assign selected parts → Magazine', 'category', () => assignParts('magazine'), active],
                ['assign_bolt', 'Assign selected parts → Bolt / slide', 'category', () => assignParts('bolt'), active],
                ['build', 'Build combat assets', 'build', buildCombatAssets, active],
                ['build_settings', 'Build settings…', 'settings', () => configureBuild(), () => true],
                ['validate', 'Validate animation…', 'fact_check', validate, active],
            ].map(([suffix, name, icon, click, condition]) => {
                const action = new Action(`${ID}_${suffix}`, {name, icon, click, condition});
                disposables.push(action);
                return action;
            });
            actions.splice(1, 0, previewToggle, firstPersonToggle);
            const menu = new BarMenu(`${ID}_menu`, actions, {name: 'Aechronis', icon: 'animation'});
            MenuBar.addMenu(menu, 'tools');
            disposables.push({delete() {
                if (MenuBar.open === menu) menu.hide();
                menu.delete();
                MenuBar.update();
            }});
        },
        onunload() {
            Codecs.project.removeListener('compile', compileCombatProject);
            for (const event of ['finished_edit', 'undo', 'redo']) Blockbench.removeListener(event, syncRigMarkers);
            Blockbench.removeListener('render_frame', refreshFirstPerson);
            Blockbench.removeListener('display_animation_frame', updateFirstPersonAccessories);
            Blockbench.removeListener('unselect_mode', firstPersonModeChanged);
            Blockbench.removeListener('unselect_project', firstPersonProjectChanged);
            Blockbench.removeListener('close_project', firstPersonProjectChanged);
            Blockbench.removeListener('save_editor_state', saveFirstPersonEditorState);
            leaveFirstPerson();
            Blockbench.removeListener('display_default_pose', displayCombatPreview);
            Blockbench.removeListener('unselect_project', clearPreview);
            clearPreview();
            if (active() && Modes.animate) Animator.preview();
            for (const item of disposables.reverse()) {
                if (item instanceof Dialog && Dialog.stack.includes(item)) item.hide();
                if (typeof item.delete === 'function') item.delete();
            }
            disposables.length = 0;
        }
    });
})();
