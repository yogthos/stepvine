// Single entry bundled by esbuild into resources/public/vendor/codemirror.js.
// Re-exports exactly the CodeMirror namespaces the app editor uses, so the
// editor loads one local module instead of seven CDN (esm.sh) imports.
export * as V from '@codemirror/view';
export * as S from '@codemirror/state';
export * as L from '@codemirror/language';
export * as C from '@codemirror/commands';
export * as HL from '@lezer/highlight';
export { clojure } from '@codemirror/legacy-modes/mode/clojure';
export { css as cssMode } from '@codemirror/legacy-modes/mode/css';
