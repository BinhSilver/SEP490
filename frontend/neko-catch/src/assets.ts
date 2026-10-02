/**
 * Public files are copied to the build output root and served under Vite's `base`
 * (/arena/app/neko/). Manifests and code keep root-relative paths like "/sprites/..."; this rebases them.
 */
export function assetUrl(path: string): string {
  if (/^(?:[a-z]+:)?\/\//i.test(path) || /^(?:data|blob):/i.test(path)) return path;
  return `${import.meta.env.BASE_URL}${path.replace(/^\/+/, '')}`;
}
