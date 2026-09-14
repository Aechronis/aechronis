import DefaultTheme from 'vitepress/theme'
import { h } from 'vue'
import LastUpdated from './LastUpdated.vue'
import './style.css'

export default {
  extends: DefaultTheme,
  Layout: () => h(DefaultTheme.Layout, null, {
    'doc-footer-before': () => h(LastUpdated),
  }),
}
