type IconName = 'sound' | 'muted' | 'pause' | 'play' | 'exit' | 'volume'

export function MemoryIcon({ name }: { name: IconName }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {(name === 'sound' || name === 'muted') && <>
        <path d="M11 5 6 9H3v6h3l5 4V5Z" />
        {name === 'sound'
          ? <><path d="M15 8a6 6 0 0 1 0 8" /><path d="M18 5a10 10 0 0 1 0 14" /></>
          : <path d="m16 9 6 6m0-6-6 6" />}
      </>}
      {name === 'pause' && <><rect x="6" y="4" width="4" height="16" rx="1" /><rect x="14" y="4" width="4" height="16" rx="1" /></>}
      {name === 'play' && <path d="m8 4 12 8-12 8V4Z" />}
      {name === 'exit' && <><path d="M10 7V4H4v16h6v-3" /><path d="M9 12h12m-4-4 4 4-4 4" /></>}
      {name === 'volume' && <><path d="M4 7h8m6 0h2M4 17h2m6 0h8" /><circle cx="15" cy="7" r="3" /><circle cx="9" cy="17" r="3" /></>}
    </svg>
  )
}
