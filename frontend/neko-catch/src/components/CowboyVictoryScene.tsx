import { useEffect, useRef, useState } from 'react';
import ending from '@neko-static/sprites/cowboy-ending/manifest.json';
import { assetUrl } from '../assets';

export const cowboyEndingCaption = `Mèo cao bồi ăn mừng · ${Math.round(ending.durationMs / 1000)} giây`;

/** Native video playback keeps the full nine-second choreography at 30 FPS. */
export function CowboyVictoryScene({ forceMotion = false }: { forceMotion?: boolean } = {}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const resumeRef = useRef(false);
  const [take, setTake] = useState(0);
  const [motionSuppressed, setMotionSuppressed] = useState(false);
  const [status, setStatus] = useState<'loading' | 'ready' | 'playing' | 'finished' | 'error'>('loading');

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    let disposed = false;
    let started = false;
    // Animations always play, whatever the device's reduced-motion setting (project rule).
    const reduced = false;
    setMotionSuppressed(reduced);
    setStatus('loading');
    resumeRef.current = false;
    video.pause();
    video.playbackRate = 1;
    video.currentTime = 0;
    const play = () => {
      video.play().catch((error: DOMException) => {
        if (!disposed && error.name !== 'AbortError') {
          setStatus(error.name === 'NotAllowedError' ? 'ready' : 'error');
        }
      });
    };
    const ready = () => {
      if (disposed || started) return;
      started = true;
      if (reduced) {
        video.currentTime = Math.max(0, video.duration - 1 / ending.fps);
        setStatus('ready');
      } else if (document.hidden) {
        resumeRef.current = true;
        setStatus('ready');
      } else play();
    };
    const visibility = () => {
      if (document.hidden && !video.paused) {
        resumeRef.current = true;
        video.pause();
      } else if (!document.hidden && resumeRef.current) {
        resumeRef.current = false;
        play();
      }
    };
    video.addEventListener('canplay', ready);
    document.addEventListener('visibilitychange', visibility);
    if (video.error) video.load();
    else if (video.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) ready();
    return () => {
      disposed = true;
      video.removeEventListener('canplay', ready);
      document.removeEventListener('visibilitychange', visibility);
      video.pause();
    };
  }, [take, forceMotion]);

  const skip = () => {
    const video = videoRef.current;
    if (!video) return;
    resumeRef.current = false;
    video.pause();
    video.currentTime = Math.max(0, (Number.isFinite(video.duration) ? video.duration : ending.durationMs / 1000) - 1 / ending.fps);
    setStatus('finished');
  };

  return (
    <div className="w-full min-w-0" data-cowboy-ending={status}>
      <video ref={videoRef} src={assetUrl(ending.path)} poster={assetUrl(ending.poster)}
        width={ending.width} height={ending.height} muted playsInline preload="auto"
        aria-label="Mèo cao bồi lộn vòng, rút súng bắn, tạo dáng, thổi khói, quay súng và cất vào bao"
        onPlaying={() => setStatus('playing')}
        onEnded={() => { resumeRef.current = false; setStatus('finished'); }}
        onError={() => setStatus('error')}
        className="block w-full max-w-[360px] mx-auto rounded-xl bg-slate-900" />
      <div className="flex flex-wrap items-center justify-between gap-2 mt-2 text-xs text-slate-300">
        <span role="status">{status === 'loading' ? 'Đang tải cảnh thắng…' : status === 'error'
          ? 'Không tải được cảnh thắng.' : motionSuppressed ? 'Máy bạn đang tắt hiệu ứng chuyển động, bấm Phát để xem.'
            : cowboyEndingCaption}</span>
        <button type="button" disabled={status === 'loading'}
          className="min-h-[44px] px-3 rounded-lg border border-slate-600 text-amber-300 hover:bg-slate-800 focus-visible:outline focus-visible:outline-2 focus-visible:outline-amber-400 disabled:opacity-50"
          onClick={() => { if (status === 'playing') skip(); else setTake(n => n + 1); }}>
          {status === 'playing' ? 'Bỏ qua' : status === 'error' ? 'Tải lại'
            : status === 'ready' ? 'Phát hoạt ảnh' : 'Xem lại'}
        </button>
      </div>
    </div>
  );
}
