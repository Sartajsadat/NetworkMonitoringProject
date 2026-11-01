import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
// Bootstrap is loaded as a swappable <link> so the RTL build can replace it for Pashto and Dari.
import bootstrapLtr from 'bootstrap/dist/css/bootstrap.min.css?url';
import bootstrapRtl from 'bootstrap/dist/css/bootstrap.rtl.min.css?url';
import 'bootstrap-icons/font/bootstrap-icons.css';
// Fonts are bundled (no external font host): Public Sans for Latin, Noto Sans Arabic for Pashto/Dari.
import '@fontsource/public-sans/latin-400.css';
import '@fontsource/public-sans/latin-600.css';
import '@fontsource/public-sans/latin-700.css';
import '@fontsource/noto-sans-arabic/arabic-400.css';
import '@fontsource/noto-sans-arabic/arabic-600.css';
import '@fontsource/noto-sans-arabic/arabic-700.css';
import './styles.css';
import App from './App.jsx';
import { AuthProvider } from './auth/AuthContext.jsx';
import { I18nProvider } from './i18n/I18nContext.jsx';

const STYLESHEETS = { ltr: bootstrapLtr, rtl: bootstrapRtl };

// Follow the operating-system light/dark preference (Bootstrap 5.3 colour modes).
const media = window.matchMedia('(prefers-color-scheme: dark)');
const applyTheme = () => document.documentElement.setAttribute('data-bs-theme', media.matches ? 'dark' : 'light');
applyTheme();
media.addEventListener('change', applyTheme);

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <I18nProvider stylesheets={STYLESHEETS}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </I18nProvider>
  </React.StrictMode>,
);
