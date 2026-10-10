'use strict';

// Read literal constructor arguments without evaluating Kotlin. Keep the
// offsets intact while hiding comments and, when finding declarations, strings.
function maskSource(source, strings = false) {
  return source.replace(/"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\/[^\n]*|\/\*[\s\S]*?\*\//g,
    token => strings || token.startsWith('//') || token.startsWith('/*') ? token.replace(/[^\n]/g, ' ') : token);
}

function stripComments(source) {
  return maskSource(source);
}

function splitArguments(source) {
  source = stripComments(source);
  const code = maskSource(source, true), parts = [], stack = [];
  let start = 0;
  for (let i = 0; i < code.length; i++) {
    const c = code[i];
    if ('([{'.includes(c)) stack.push(c);
    const closing = ')]}'.indexOf(c);
    if (closing !== -1 && stack.pop() !== '([{'[closing]) throw new Error('Mismatched Kotlin argument delimiter');
    if (c === ',' && stack.length === 0) { parts.push(source.slice(start, i).trim()); start = i + 1; }
  }
  if (stack.length) throw new Error('Unclosed Kotlin argument');
  parts.push(source.slice(start).trim());
  return parts.filter(Boolean);
}

function callArguments(source, open) {
  source = stripComments(source);
  const code = maskSource(source, true);
  if (code[open] !== '(') throw new Error('Expected Kotlin constructor opening parenthesis');
  let depth = 1;
  for (let i = open + 1; i < code.length; i++) {
    if (code[i] === '(') depth++;
    if (code[i] === ')' && --depth === 0) return source.slice(open + 1, i);
  }
  throw new Error('Unclosed Kotlin constructor');
}

function namedArguments(body) {
  return Object.fromEntries(splitArguments(body).map(part => {
    const match = part.match(/^(?:override\s+)?(?:val\s+|var\s+)?(\w+)(?:\s*:[^=]+)?\s*=\s*([\s\S]+)$/);
    return match ? [match[1], match[2].trim()] : null;
  }).filter(Boolean));
}

function definitions(source, types) {
  if (!types.length) return [];
  const pattern = new RegExp(`\\bval\\s+(\\w+)(?:\\s*:\\s*[\\w.]+)?\\s*=\\s*(${types.join('|')})\\s*\\(`, 'g');
  return [...maskSource(source, true).matchAll(pattern)].map(match => ({
    key: match[1], type: match[2], args: namedArguments(callArguments(source, match.index + match[0].length - 1)),
  }));
}

function constructorDefaults(source, type) {
  const match = maskSource(source, true).match(new RegExp(`\\bclass\\s+${type}\\s*\\(`));
  if (!match) throw new Error(`Missing constructor: ${type}`);
  return namedArguments(callArguments(source, match.index + match[0].length - 1));
}

function constantValue(source, name) {
  const match = maskSource(source, true).match(new RegExp(`\\bconst\\s+val\\s+${name}(?:\\s*:[^=\\n]+)?\\s*=`));
  if (!match) throw new Error(`Missing Kotlin constant: ${name}`);
  return stripComments(source).slice(match.index + match[0].length).trimStart().split(/[\n;]/, 1)[0].trim();
}

function number(value, label) {
  if (!/^[+-]?(?:\d[\d_]*(?:\.\d[\d_]*)?|\.\d+)(?:[eE][+-]?\d+)?[fFlL]?$/.test(value ?? '')) {
    throw new Error(`Expected numeric literal for ${label}: ${value}`);
  }
  const result = Number(value.replaceAll('_', '').replace(/[fFlL]$/, ''));
  if (!Number.isFinite(result)) throw new Error(`Expected finite numeric literal for ${label}: ${value}`);
  return result;
}

function string(value, label) {
  if (!/^"(?:\\.|[^"\\$])*"$/.test(value ?? '')) throw new Error(`Expected string literal for ${label}: ${value}`);
  return JSON.parse(value.replace(/\\(?:u[0-9a-fA-F]{4}|.)/g, escape => escape === '\\$' || escape === "\\'" ? escape[1] : escape));
}

function boolean(value, label) {
  if (value !== 'true' && value !== 'false') throw new Error(`Expected boolean literal for ${label}: ${value}`);
  return value === 'true';
}

function displayName(value) {
  const match = value?.match(/^Component\.text\(\s*("(?:\\.|[^"\\])*")/);
  if (!match) throw new Error(`Expected Component.text name: ${value}`);
  return string(match[1], 'display name');
}

module.exports = { splitArguments, stripComments, callArguments, namedArguments, definitions, constructorDefaults, constantValue, number, string, boolean, displayName };
