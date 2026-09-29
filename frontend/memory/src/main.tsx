import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'

const rootElement = document.getElementById('memory-root')

if (!rootElement) {
  throw new Error('Không tìm thấy #memory-root')
}

const sessionId = rootElement.dataset.sessionId

if (!sessionId) {
  throw new Error('Không tìm thấy ván chơi. Hãy mở lại từ trang Trò chơi.')
}

createRoot(rootElement).render(
  <App sessionId={sessionId} backUrl={rootElement.dataset.backUrl || '/games'} />
)
