<script setup>
import { computed, onMounted, ref } from 'vue'
import { createTebexClient, loadCheckout, secureUrl } from './tebex.mjs'

// Tebex Headless API public token
const token = '13113-63c5f6366fb86475cf2f5d191ad909903b062ce1'
const client = createTebexClient(token)
const packages = ref([])
const selected = ref([])
const username = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const basket = ref(null)
const checkoutReady = ref(false)
let tebex

const cart = computed(() => packages.value.filter(item => selected.value.includes(item.id)))
const categories = computed(() => [...new Set(packages.value.map(item => item.category?.name || 'Packages'))])
const checkoutUrl = computed(() => secureUrl(basket.value?.links?.checkout))

function money(amount, currency) {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount)
}

function plainDescription(html) {
  const document = new DOMParser().parseFromString(html || '', 'text/html')
  document.querySelectorAll('script, style, iframe, object').forEach(node => node.remove())
  document.querySelectorAll('p, br, li, div, h1, h2, h3').forEach(node => node.prepend('\n'))
  return document.body.textContent.trim()
}

async function loadPackages() {
  if (!token) return
  loading.value = true
  error.value = ''
  try {
    const result = await client.packages()
    if (!Array.isArray(result)) throw new Error('The store could not load its packages. Please try again.')
    packages.value = result.map(item => ({ ...item, description: plainDescription(item.description) }))
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'The store is unavailable. Please try again.'
  } finally { loading.value = false }
}

function editBasket() {
  basket.value = null
  checkoutReady.value = false
  error.value = ''
  notice.value = ''
}

function togglePackage(id) {
  editBasket()
  selected.value = selected.value.includes(id)
    ? selected.value.filter(value => value !== id)
    : [...selected.value, id]
}

async function prepare() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  basket.value = null
  checkoutReady.value = false
  try {
    const returnUrl = new URL(window.location.href)
    returnUrl.search = ''
    returnUrl.hash = ''
    basket.value = await client.prepare(username.value.trim(), selected.value, returnUrl.href)
    tebex = await loadCheckout()
    checkoutReady.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'Checkout is unavailable. Please try again.'
  } finally { busy.value = false }
}

function launch() {
  if (!checkoutReady.value || !basket.value) return
  try {
    // Keep launch synchronous with the click so mobile popups are not blocked.
    tebex.checkout.init({ ident: basket.value.ident, theme: 'auto' })
    tebex.checkout.launch()
  } catch {
    error.value = 'The payment window could not open. Use the secure checkout link below.'
  }
}

onMounted(() => {
  if (new URLSearchParams(window.location.search).get('checkout') === 'returned') {
    notice.value = 'You have returned from checkout. Check your Tebex receipt for your payment status.'
  }
  loadPackages()
})
</script>

<template>
  <div class="store">
    <p v-if="notice" role="status" class="store-message">{{ notice }}</p>
    <p v-if="!token" class="store-message">The store is not open yet. Please check back soon.</p>
    <template v-else>
      <p v-if="error" role="alert" class="store-message store-error">{{ error }}</p>
      <p v-if="loading" role="status">Loading packages…</p>
      <button v-else-if="!packages.length && error" type="button" @click="loadPackages">Try again</button>
      <p v-else-if="!packages.length">There are no packages available right now.</p>
      <div v-else class="store-layout">
        <div>
          <section v-for="category in categories" :key="category" class="store-category">
            <h2>{{ category }}</h2>
            <div class="store-products">
              <article v-for="item in packages.filter(item => (item.category?.name || 'Packages') === category)" :key="item.id" class="store-product">
                <img v-if="secureUrl(item.image)" :src="secureUrl(item.image)" :alt="item.name" loading="lazy" />
                <h3>{{ item.name }}</h3>
                <p class="store-price">{{ money(item.total_price, item.currency) }}</p>
                <p v-if="item.type === 'subscription' || item.type === 'both'" class="store-note">{{ item.type === 'subscription' ? 'Recurring payment.' : 'Subscription option available.' }} Review the billing interval and terms at checkout.</p>
                <p class="store-description">{{ item.description }}</p>
                <button type="button" :disabled="busy" :aria-pressed="selected.includes(item.id)" @click="togglePackage(item.id)">
                  {{ selected.includes(item.id) ? 'Remove from basket' : 'Add to basket' }}
                </button>
              </article>
            </div>
          </section>
        </div>
        <aside class="store-basket" aria-labelledby="basket-heading" :aria-busy="busy">
          <h2 id="basket-heading">Your basket</h2>
          <p v-if="!cart.length">Choose a package to get started.</p>
          <ul v-else>
            <li v-for="item in cart" :key="item.id">
              <span>{{ item.name }} × 1</span>
              <button type="button" class="store-remove" :disabled="busy" :aria-label="`Remove ${item.name}`" @click="togglePackage(item.id)">Remove</button>
            </li>
          </ul>
          <form @submit.prevent="prepare">
            <label for="minecraft-username">Minecraft Java username</label>
            <input id="minecraft-username" v-model="username" name="username" autocomplete="off" autocapitalize="none" :spellcheck="false" pattern="[A-Za-z0-9_]{3,16}" maxlength="16" required :disabled="busy" aria-describedby="username-help" @input="editBasket" />
            <p id="username-help" class="store-note">Purchases are for this account. Check the spelling before continuing.</p>
            <button v-if="!basket" type="submit" class="store-primary" :disabled="busy || !cart.length">{{ busy ? 'Preparing checkout…' : 'Review checkout' }}</button>
          </form>
          <div v-if="basket" class="store-review" aria-live="polite">
            <p>For <strong>{{ basket.username || username.trim() }}</strong></p>
            <p class="store-total">Total: {{ money(basket.total_price, basket.currency) }}</p>
            <p class="store-note">Final taxes, discounts and any recurring payment terms are shown at checkout.</p>
            <button v-if="checkoutReady" type="button" class="store-primary" @click="launch">Continue to payment</button>
            <p v-else-if="busy" role="status">Loading secure checkout…</p>
            <p v-if="checkoutUrl"><a :href="checkoutUrl" target="_blank" rel="noopener noreferrer">Open secure checkout in a new tab</a></p>
            <button type="button" :disabled="busy" @click="editBasket">Edit basket</button>
          </div>
          <p class="store-note">Payments are handled by Tebex.</p>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.store { margin-top: 1.5rem; }
.store-layout { display: grid; grid-template-columns: minmax(0, 2fr) minmax(240px, 1fr); gap: 1.5rem; align-items: start; }
.store-category:first-child h2, .store-basket h2 { margin-top: 0; border-top: 0; padding-top: 0; }
.store-products { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr)); gap: 1rem; }
.store-product, .store-basket { padding: 1.25rem; border: 1px solid var(--vp-c-divider); border-radius: 12px; }
.store-product { display: flex; flex-direction: column; align-items: start; }
.store-product img { width: 100%; height: 160px; object-fit: contain; border-radius: 6px; }
.store-product h3 { margin-top: .75rem; }
.store-price { font-size: 1.2rem; font-weight: 600; margin: .5rem 0; }
.store-description { white-space: pre-line; overflow-wrap: anywhere; }
.store-product > button { margin-top: auto; }
.store-basket { background: var(--vp-c-bg-soft); position: sticky; top: 90px; }
.store-basket ul { padding: 0; list-style: none; }
.store-basket li { display: flex; align-items: baseline; justify-content: space-between; gap: .5rem; overflow-wrap: anywhere; }
.store button { border: 1px solid var(--vp-c-divider); border-radius: 6px; padding: .5rem .8rem; font: inherit; font-size: .875rem; cursor: pointer; background: var(--vp-c-bg); }
.store button:hover:not(:disabled) { border-color: var(--vp-c-brand-1); }
.store button:disabled { opacity: .55; cursor: not-allowed; }
.store .store-primary { background: var(--vp-c-brand-1); border-color: var(--vp-c-brand-1); color: var(--vp-c-white); width: 100%; }
.store button:focus-visible, .store input:focus-visible { outline: 2px solid var(--vp-c-brand-1); outline-offset: 3px; }
.store .store-remove { padding: .2rem; border: 0; background: transparent; color: var(--vp-c-brand-1); }
.store label { display: block; font-weight: 600; font-size: .875rem; }
.store input { width: 100%; border: 1px solid var(--vp-c-divider); border-radius: 6px; padding: .6rem; background: var(--vp-c-bg); margin-top: .5rem; font: inherit; }
.store-note { font-size: .875rem; line-height: 1.5; color: var(--vp-c-text-2); }
.store-message { border-radius: 8px; padding: 1rem; background: var(--vp-c-bg-soft); }
.store-error { border: 1px solid var(--vp-c-danger-1); }
.store-total { font-size: 1.15rem; font-weight: 600; }
@media (max-width: 760px) { .store-layout { grid-template-columns: 1fr; } .store-basket { position: static; } }
</style>
