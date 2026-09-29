import React, { useState } from 'react';
import { initials } from '../lib/text';

/** Player avatar: the uploaded picture, or the name's initials when there is none (or it fails to load). */
export const Avatar: React.FC<{ name: string; url: string | null; size?: number; ring?: string }> = ({
  name,
  url,
  size = 32,
  ring = 'rgba(255,255,255,0.35)',
}) => {
  const [failed, setFailed] = useState(false);
  const style: React.CSSProperties = { width: size, height: size, boxShadow: `0 0 0 2px ${ring}` };
  if (url && !failed) {
    return (
      <img
        src={url}
        alt=""
        aria-hidden="true"
        onError={() => setFailed(true)}
        className="rounded-full object-cover shrink-0 bg-slate-800"
        style={style}
      />
    );
  }
  return (
    <span
      aria-hidden="true"
      className="rounded-full shrink-0 flex items-center justify-center bg-gradient-to-br from-amber-400 to-orange-600 text-white font-black"
      style={{ ...style, fontSize: Math.max(10, Math.round(size * 0.38)) }}
    >
      {initials(name)}
    </span>
  );
};
