const path = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');

module.exports = {
  entry: {
    app: './js/app.js',
  },
  output: {
    path: path.resolve(__dirname, 'dist'),
    clean: true,
    filename: 'js/app.js',
  },
  // Owns the single <script> tag for the bundle (injected into index.html).
  // Lives here, not in the prod config, so dev gets the generated HTML too —
  // otherwise the static template's manual <script> double-loaded the app.
  plugins: [
    new HtmlWebpackPlugin({
      template: './index.html',
    }),
  ],
};
