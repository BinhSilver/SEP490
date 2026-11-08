/**
 * Public files are copied to the build output root and served under Vite's `base`
 * (/arena/app/neko/). Manifests and code keep root-relative paths like "/sprites/..."; this rebases them.
 */
let assetBase = import.meta.env.BASE_URL;
/** Shared renderers can also be hosted by the admin effect viewer. */
export function setAssetBase(base: string) { assetBase = base; }
export function assetUrl(path: string): string {
  if (path.startsWith('/uploads/') || path.startsWith('/arena/')) return path;
  if (/^(?:[a-z]+:)?\/\//i.test(path) || /^(?:data|blob):/i.test(path)) return path;
  return `${assetBase}${path.replace(/^\/+/, '')}`;
}
