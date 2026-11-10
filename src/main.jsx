import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'
import { locale } from './i18n/index.js'

// Screen readers and hyphenation follow <html lang>; match the dictionary in use.
document.documentElement.lang = locale


createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
