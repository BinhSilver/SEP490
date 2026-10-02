/** One cancellable rAF loop; no wall-clock changes or setInterval drift. */
export function createTimer({ frame = requestAnimationFrame, cancel = cancelAnimationFrame, now = () => performance.now() } = {}) {
  let handle = null, generation = 0;
  function stop() { generation++; if (handle !== null) cancel(handle); handle = null; }
  function start(remainingMs, onTick, onEnd) {
    stop();
    const token = generation, deadline = now() + Math.max(0, remainingMs);
    function tick() {
      if (token !== generation) return;
      const left = Math.max(0, deadline - now());
      onTick(left);
      if (left <= 0) { handle = null; onEnd(); }
      else handle = frame(tick);
    }
    handle = frame(tick);
  }
  return { start, stop };
}
