const API = 'https://headless.tebex.io/api'

export function createTebexClient(token, fetcher = fetch) {
  const account = `/accounts/${encodeURIComponent(token)}`
  async function request(path, body) {
    const response = await fetcher(`${API}${path}`, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      signal: AbortSignal.timeout(20000),
    })
    const result = await response.json().catch(() => null)
    if (!response.ok) {
      const detail = result?.detail ?? result?.message ?? result?.error
      throw new Error(typeof detail === 'string' ? detail : 'Tebex could not process this request. Please try again.')
    }
    if (!result) throw new Error('Tebex returned an empty response. Please try again.')
    return result.data ?? result
  }
  return {
    packages: () => request(`${account}/packages`),
    async prepare(username, packageIds, returnUrl) {
      if (!/^[A-Za-z0-9_]{3,16}$/.test(username)) throw new Error('Enter a valid Minecraft Java username.')
      if (!packageIds.length) throw new Error('Add a package to your basket first.')
      const completeUrl = new URL(returnUrl)
      completeUrl.searchParams.set('checkout', 'returned')
      const basket = await request(`${account}/baskets`, {
        username,
        complete_url: completeUrl.href,
        cancel_url: returnUrl,
        complete_auto_redirect: true,
      })
      if (!basket.ident) throw new Error('Tebex did not create a basket. Please try again.')
      const ident = encodeURIComponent(basket.ident)
      // Each attempt starts a fresh basket: a failed or ambiguous add is never retried
      // against an existing basket, avoiding accidental duplicate purchases.
      for (const id of new Set(packageIds)) {
        await request(`/baskets/${ident}/packages`, { package_id: String(id), quantity: 1 })
      }
      const ready = await request(`${account}/baskets/${ident}`)
      if (ready.complete || !ready.links?.checkout) throw new Error('This basket is no longer available. Please try again.')
      return ready
    },
  }
}

let checkoutScript
export function loadCheckout() {
  if (window.Tebex) return Promise.resolve(window.Tebex)
  if (checkoutScript) return checkoutScript
  checkoutScript = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    const timeout = setTimeout(() => fail(), 15000)
    function fail() {
      clearTimeout(timeout)
      script.remove()
      checkoutScript = undefined
      reject(new Error('The payment window could not load. Please try again or use the secure checkout link.'))
    }
    script.src = 'https://js.tebex.io/v/1.js'
    script.async = true
    script.onload = () => {
      clearTimeout(timeout)
      if (window.Tebex) resolve(window.Tebex)
      else fail()
    }
    script.onerror = fail
    document.head.appendChild(script)
  })
  return checkoutScript
}

export function secureUrl(value) {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' ? url.href : ''
  } catch { return '' }
}
