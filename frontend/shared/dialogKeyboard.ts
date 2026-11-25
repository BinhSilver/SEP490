/** Keep Tab in the visible controls of a game dialog, including at the browser-chrome boundary. */
export function containDialogTab(event: Pick<KeyboardEvent, 'key' | 'shiftKey' | 'preventDefault'> & { currentTarget: HTMLElement }) {
  if (event.key !== 'Tab') return;
  const nodes = [...event.currentTarget.querySelectorAll<HTMLElement>('button:not([disabled]),a[href],input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex="0"]')].filter(node => node.getClientRects().length);
  if (!nodes.length) return;
  const index = nodes.indexOf(document.activeElement as HTMLElement);
  if (event.shiftKey && index <= 0) { event.preventDefault(); nodes[nodes.length - 1].focus(); }
  else if (!event.shiftKey && (index === nodes.length - 1 || index < 0)) { event.preventDefault(); nodes[0].focus(); }
}
