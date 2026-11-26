/** React owns this markup so changing balances never replaces React's text nodes from outside the tree. */
export function CpText({ text }: { text: string }) {
  return <>{text.split(/(\bCP\b)/).map((part, i) => part === 'CP'
    ? <span key={i} className="jlas-cp-unit"><span className="jlas-cp-icon" aria-hidden="true" />CP</span>
    : part)}</>;
}
