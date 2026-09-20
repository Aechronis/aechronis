const { merge } = require('webpack-merge');
const common = require('./webpack.common.js');

module.exports = merge(common, {
  mode: 'development',
  devtool: 'inline-source-map',
  devServer: {
    liveReload: true,
    hot: true,
    open: true,
    // Ignore nodes/*.json from the static watcher so editing the data files
    // doesn't trigger a page reload — we want the in-app 30s poller to pick
    // the change up instead, which is the path that runs in production.
    // Other static files (index.html, css/, etc.) still live-reload normally.
    static: {
      directory: './',
      watch: { ignored: '**/nodes/**' },
    },
  },
});
