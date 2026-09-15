// Read literal constructor arguments without evaluating Kotlin. Nested calls,
// strings and comments must not be mistaken for argument separators.
export function splitArguments(source) {
  const parts = []
  let start = 0, depth = 0, quote = null
  for (let i = 0; i < source.length; i++) {
    const c = source[i]
    if (quote) {
      if (c === '\\') i++
      else if (c === quote) quote = null
      continue
    }
    if (c === '"' || c === "'") { quote = c; continue }
    if ('([{'.includes(c)) depth++
    if (')]}'.includes(c)) depth--
    if (c === ',' && depth === 0) { parts.push(source.slice(start, i).trim()); start = i + 1 }
  }
  parts.push(source.slice(start).trim())
  return parts.filter(Boolean)
}

export function stripComments(source) {
  return source.replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\/[^\n]*|\/\*[\s\S]*?\*\//g,
    token => token.startsWith('//') || token.startsWith('/*') ? token.replace(/[^\n]/g, ' ') : token)
}

export function callArguments(source, open) {
  let depth = 1, quote = null
  for (let i = open + 1; i < source.length; i++) {
    const c = source[i]
    if (quote) {
      if (c === '\\') i++
      else if (c === quote) quote = null
      continue
    }
    if (c === '"' || c === "'") { quote = c; continue }
    if (c === '(') depth++
    if (c === ')') {
      depth--
      if (depth === 0) return source.slice(open + 1, i)
    }
  }
  throw new Error('Unclosed Kotlin constructor')
}

export function namedArguments(body) {
  return Object.fromEntries(splitArguments(body).map(part => {
    const match = part.match(/^(?:override\s+)?(?:val\s+|var\s+)?(\w+)(?:\s*:[^=]+)?\s*=\s*([\s\S]+)$/)
    return match ? [match[1], match[2].trim()] : null
  }).filter(Boolean))
}

export function definitions(source, types) {
  source = stripComments(source)
  const pattern = new RegExp(`\\bval\\s+(\\w+)\\s*=\\s*(${types.join('|')})\\s*\\(`, 'g')
  return [...source.matchAll(pattern)].map(match => ({
    key: match[1], type: match[2], args: namedArguments(callArguments(source, match.index + match[0].length - 1)),
  }))
}

export function number(value, label) {
  if (!/^[+-]?(?:\d[\d_]*(?:\.\d[\d_]*)?|\.\d+)(?:[eE][+-]?\d+)?[fFlL]?$/.test(value ?? '')) {
    throw new Error(`Expected numeric literal for ${label}: ${value}`)
  }
  return Number(value.replaceAll('_', '').replace(/[fFlL]$/, ''))
}

export function string(value, label) {
  if (!/^"[^"$]*"$/.test(value ?? '')) throw new Error(`Expected string literal for ${label}: ${value}`)
  return JSON.parse(value)
}

export function displayName(value) {
  const match = value?.match(/^Component\.text\(\s*("(?:\\.|[^"\\])*")/)
  if (!match) throw new Error(`Expected Component.text name: ${value}`)
  return JSON.parse(match[1])
}
