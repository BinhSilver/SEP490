import { useEffect, useRef, useState } from 'react'
import { containDialogTab } from '../../shared/dialogKeyboard'

/** Native modal containment and focus return, with the same boolean decision as the former browser prompt. */
export function useGameConfirmation() {
  const dialog = useRef<HTMLDialogElement>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [title, setTitle] = useState('Xác nhận rời ván')
  const decision = useRef<((value: boolean) => void) | null>(null)
  const finish = (value: boolean) => {
    const resolve = decision.current
    decision.current = null
    setMessage(null)
    resolve?.(value)
  }
  useEffect(() => {
    if (message && !dialog.current?.open) dialog.current?.showModal()
    else if (!message && dialog.current?.open) dialog.current.close()
  }, [message])
  useEffect(() => () => { decision.current?.(false); decision.current = null }, [])
  return {
    confirm: (text: string, heading = 'Xác nhận rời ván') => new Promise<boolean>(resolve => { decision.current = resolve; setTitle(heading); setMessage(text) }),
    dialog: <dialog ref={dialog} className="memory-confirm" data-game-ui-panel aria-labelledby="memory-confirm-title"
      onKeyDown={containDialogTab} onCancel={() => finish(false)} onClose={() => finish(false)}>
      <h2 id="memory-confirm-title">{title}</h2>
      <p>{message}</p>
      <div className="memory-confirm__actions">
        <button type="button" autoFocus onClick={() => finish(false)}>Hủy</button>
        <button type="button" data-game-ui-primary onClick={() => finish(true)}>Xác nhận</button>
      </div>
    </dialog>
  }
}
