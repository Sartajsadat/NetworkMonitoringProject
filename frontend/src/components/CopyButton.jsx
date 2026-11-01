import { useState } from 'react';
import { useI18n } from '../i18n/I18nContext.jsx';

export default function CopyButton({ text, targetId }) {
  const { t } = useI18n();
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard blocked (e.g. plain http): select the text so the user can press Ctrl+C.
      const el = targetId && document.getElementById(targetId);
      if (el) {
        const range = document.createRange();
        range.selectNodeContents(el);
        const sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      }
    }
  };
  return (
    <button type="button" className="btn btn-sm btn-outline-secondary" onClick={copy}>
      <i className={`bi bi-${copied ? 'check2' : 'clipboard'} me-1`} aria-hidden="true" />
      {copied ? t('common.copied') : t('common.copy')}
    </button>
  );
}
