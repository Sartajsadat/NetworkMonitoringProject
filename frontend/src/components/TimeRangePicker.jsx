import { useState } from 'react';
import { useI18n } from '../i18n/I18nContext.jsx';

export const PRESETS = [
  { key: '5m', seconds: 300 },
  { key: '15m', seconds: 900 },
  { key: '1h', seconds: 3600 },
  { key: '24h', seconds: 86400 },
  { key: '7d', seconds: 604800 },
];

/** Converts a range value into concrete ISO times. Presets are relative to "now" (live). */
export function resolveRange(range, now = Date.now()) {
  if (range.preset) {
    const p = PRESETS.find((x) => x.key === range.preset);
    return { from: new Date(now - p.seconds * 1000).toISOString(), to: new Date(now).toISOString(), live: true };
  }
  return { from: range.from, to: range.to, live: false };
}

const toLocalInput = (iso) => {
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};

export default function TimeRangePicker({ value, onChange }) {
  const { t } = useI18n();
  const [custom, setCustom] = useState(false);
  const resolved = resolveRange(value);
  const [from, setFrom] = useState(toLocalInput(resolved.from));
  const [to, setTo] = useState(toLocalInput(resolved.to));
  return (
    <div className="d-flex flex-wrap align-items-center gap-2">
      <div className="btn-group btn-group-sm" role="group" aria-label={t('range.label')}>
        {PRESETS.map((p) => (
          <button key={p.key} type="button" aria-pressed={value.preset === p.key}
            className={`btn ${value.preset === p.key ? 'btn-primary' : 'btn-outline-secondary'}`}
            onClick={() => { setCustom(false); onChange({ preset: p.key }); }}>
            {t(`range.${p.key}`)}
          </button>
        ))}
        <button type="button" aria-pressed={!value.preset || custom}
          className={`btn ${!value.preset || custom ? 'btn-primary' : 'btn-outline-secondary'}`} onClick={() => setCustom((c) => !c)}>
          {t('range.custom')}
        </button>
      </div>
      {custom && (
        <form className="d-flex flex-wrap align-items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            onChange({ from: new Date(from).toISOString(), to: new Date(to).toISOString() });
          }}>
          <label className="small text-body-secondary" htmlFor="range-from">{t('range.from')}</label>
          <input id="range-from" type="datetime-local" dir="ltr" className="form-control form-control-sm" style={{ width: 'auto' }}
            value={from} onChange={(e) => setFrom(e.target.value)} required />
          <label className="small text-body-secondary" htmlFor="range-to">{t('range.to')}</label>
          <input id="range-to" type="datetime-local" dir="ltr" className="form-control form-control-sm" style={{ width: 'auto' }}
            value={to} onChange={(e) => setTo(e.target.value)} required />
          <button type="submit" className="btn btn-sm btn-primary">{t('common.apply')}</button>
        </form>
      )}
    </div>
  );
}
