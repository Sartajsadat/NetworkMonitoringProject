import { useCallback, useEffect, useState } from 'react';
import { captureApi } from '../../api/endpoints.js';
import { errorMessage } from '../../api/client.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import Pagination from '../../components/Pagination.jsx';
import PacketDetail from './PacketDetail.jsx';
import { DISPLAY_FILTER_PRESETS, endpoint, packetTime } from './captureHelpers.js';

const EMPTY = { host: '', protocol: '', port: '', text: '' };

export default function PacketsTab({ capture, live, canSeeBytes }) {
  const { t, formatNumber } = useI18n();
  const [draft, setDraft] = useState(EMPTY);
  const [quick, setQuick] = useState(EMPTY);
  const [filterDraft, setFilterDraft] = useState('');
  const [displayFilter, setDisplayFilter] = useState('');
  const [filterError, setFilterError] = useState(null);
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [selected, setSelected] = useState(null);
  const [presetsOpen, setPresetsOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const res = await captureApi.packets(capture.id, {
        page, size: 100,
        host: quick.host || undefined, protocol: quick.protocol || undefined,
        port: quick.port === '' ? undefined : quick.port, text: quick.text || undefined,
        displayFilter: displayFilter || undefined,
      });
      setData(res);
      setError(null);
    } catch (e) {
      if (e?.response?.data?.code === 'INVALID_FILTER') {
        setFilterError(e.response.data.message);
        setDisplayFilter('');
      } else {
        setError(e);
      }
    } finally {
      setLoading(false);
    }
  }, [capture.id, page, quick, displayFilter]);

  useEffect(() => { load(); }, [load]);
  // A new filter gives a new list: close the detail of a packet that may no longer be in it.
  useEffect(() => { setSelected(null); }, [quick, displayFilter]);

  // While the capture runs, refresh the first page of the unfiltered list as new pieces arrive.
  const following = live && page === 0 && !displayFilter;
  useEffect(() => {
    if (!following) return undefined;
    const timer = setInterval(load, 5000);
    return () => clearInterval(timer);
  }, [following, load]);

  const applyDisplayFilter = async (value) => {
    const f = value.trim();
    setFilterError(null);
    if (!f) {
      setDisplayFilter('');
      setPage(0);
      return;
    }
    try {
      const check = await captureApi.validateFilter(f);
      if (!check.valid) {
        setFilterError(check.message);
        return;
      }
      setPage(0);
      setDisplayFilter(f);
    } catch (e) {
      setFilterError(errorMessage(e, t));
    }
  };

  const setD = (k) => (e) => setDraft((d) => ({ ...d, [k]: e.target.value }));

  return (
    <>
      <div className="card mb-3"><div className="card-body">
        <form className="mb-2" onSubmit={(e) => { e.preventDefault(); applyDisplayFilter(filterDraft); }}>
          <label className="form-label small fw-semibold" htmlFor="df-input">{t('analyzer.displayFilter')}</label>
          <div className="input-group input-group-sm">
            <span className={`input-group-text ${displayFilter ? 'text-bg-success' : ''}`}><i className="bi bi-funnel" aria-hidden="true" /></span>
            <input id="df-input" className={`form-control nm-mono ${filterError ? 'is-invalid' : ''}`} dir="ltr" maxLength={1000}
              value={filterDraft} onChange={(e) => setFilterDraft(e.target.value)} placeholder="tcp.flags.reset == 1 && ip.addr == 10.0.0.10"
              aria-describedby="df-help" />
            <button type="submit" className="btn btn-primary">{t('common.apply')}</button>
            <button type="button" className="btn btn-outline-secondary" onClick={() => { setFilterDraft(''); applyDisplayFilter(''); }}>{t('common.clear')}</button>
            <button type="button" className="btn btn-outline-secondary dropdown-toggle" aria-expanded={presetsOpen}
              aria-haspopup="true" onClick={() => setPresetsOpen((o) => !o)}>{t('analyzer.presets')}</button>
            <ul className={`dropdown-menu dropdown-menu-end nm-presets ${presetsOpen ? 'show' : ''}`}>
              {DISPLAY_FILTER_PRESETS.map((p) => (
                <li key={p.key}>
                  <button type="button" className="dropdown-item small" onClick={() => {
                    setPresetsOpen(false);
                    setFilterDraft(p.filter);
                    applyDisplayFilter(p.filter);
                  }}>
                    {t(`analyzer.preset.${p.key}`)} <code className="ms-1 nm-ltr" dir="ltr">{p.filter}</code>
                  </button>
                </li>
              ))}
            </ul>
            {filterError && <div className="invalid-feedback d-block nm-ltr" dir="ltr">{filterError}</div>}
          </div>
          <div id="df-help" className="form-text">{t('analyzer.displayFilterHelp')}</div>
        </form>

        <form className="row g-2 align-items-end" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuick(draft); }}>
          <div className="col-sm-6 col-lg-3">
            <label className="form-label small" htmlFor="q-host">{t('analyzer.host')}</label>
            <input id="q-host" className="form-control form-control-sm nm-ltr" dir="ltr" maxLength={64} value={draft.host} onChange={setD('host')} placeholder="10.0.0.10" />
          </div>
          <div className="col-sm-6 col-lg-2">
            <label className="form-label small" htmlFor="q-proto">{t('analyzer.protocol')}</label>
            <input id="q-proto" className="form-control form-control-sm nm-ltr" dir="ltr" maxLength={40} value={draft.protocol} onChange={setD('protocol')} placeholder="TCP, DNS, HTTP" />
          </div>
          <div className="col-sm-4 col-lg-2">
            <label className="form-label small" htmlFor="q-port">{t('analyzer.port')}</label>
            <input id="q-port" type="number" min={0} max={65535} className="form-control form-control-sm" value={draft.port} onChange={setD('port')} />
          </div>
          <div className="col-sm-8 col-lg-3">
            <label className="form-label small" htmlFor="q-text">{t('analyzer.text')}</label>
            <input id="q-text" className="form-control form-control-sm" maxLength={200} value={draft.text} onChange={setD('text')} />
          </div>
          <div className="col-lg-2 d-flex gap-1">
            <button type="submit" className="btn btn-sm btn-outline-primary">{t('common.search')}</button>
            <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => { setDraft(EMPTY); setQuick(EMPTY); setPage(0); }}>{t('common.clear')}</button>
          </div>
        </form>
      </div></div>

      <ErrorAlert error={error} onRetry={load} />
      {!data && loading && <LoadingSpinner />}
      {data && (
        <div className="card">
          <div className="card-header d-flex justify-content-between align-items-center flex-wrap gap-2 small">
            <span>
              {t('analyzer.matching', { n: formatNumber(data.totalElements), total: formatNumber(data.packetsIndexed) })}
              {displayFilter && <> — <code className="nm-ltr" dir="ltr">{displayFilter}</code></>}
            </span>
            <span className="d-flex gap-2 align-items-center">
              {following && <span className="badge text-bg-success"><i className="bi bi-broadcast me-1" aria-hidden="true" />{t('analyzer.following')}</span>}
              {loading && <span className="spinner-border spinner-border-sm" aria-label={t('common.loading')} />}
            </span>
          </div>
          {data.displayFilterTruncated && <div className="alert alert-warning m-2 small">{t('analyzer.filterTruncated')}</div>}
          {data.indexTruncated && <div className="alert alert-info m-2 small">{t('analyzer.indexTruncated')}</div>}
          {data.content.length === 0 ? (
            <EmptyState icon="funnel" title={data.packetsIndexed === 0 ? t('analyzer.noPacketsYet') : t('analyzer.noMatch')} />
          ) : (
            <div className="table-responsive nm-packets">
              <table className="table table-sm table-hover nm-table mb-0" dir="ltr">
                <thead><tr>
                  <th>{t('analyzer.col.no')}</th><th>{t('analyzer.col.time')}</th><th>{t('analyzer.col.source')}</th>
                  <th>{t('analyzer.col.destination')}</th><th>{t('analyzer.col.protocol')}</th>
                  <th className="text-end">{t('analyzer.col.length')}</th><th>{t('analyzer.col.info')}</th>
                </tr></thead>
                <tbody>
                  {data.content.map((p) => (
                    <tr key={p.frameNo} onClick={() => setSelected(p.frameNo)} tabIndex={0}
                      onKeyDown={(e) => e.key === 'Enter' && setSelected(p.frameNo)}
                      className={`nm-click ${selected === p.frameNo ? 'table-active' : ''} ${p.reset ? 'nm-pkt-reset' : ''} ${p.retransmission ? 'nm-pkt-retrans' : ''}`}
                      aria-selected={selected === p.frameNo}>
                      <td className="nm-num">{p.frameNo}</td>
                      <td className="nm-mono small text-nowrap">{packetTime(p.time)}</td>
                      <td className="nm-mono small text-nowrap">{endpoint(p.src, p.srcPort)}</td>
                      <td className="nm-mono small text-nowrap">{endpoint(p.dst, p.dstPort)}</td>
                      <td className="small"><span className="badge text-bg-light border">{p.protocol}</span></td>
                      <td className="text-end nm-num small">{p.length}</td>
                      <td className="small nm-info">
                        {p.reset && <span className="badge text-bg-danger me-1">RST</span>}
                        {p.retransmission && <span className="badge text-bg-warning me-1">{t('analyzer.retransmission')}</span>}
                        {p.info}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="card-footer"><Pagination data={data} onPage={(n) => { setPage(n); setSelected(null); }} /></div>
        </div>
      )}
      {selected != null && (
        <PacketDetail captureId={capture.id} frame={selected} canSeeBytes={canSeeBytes} onClose={() => setSelected(null)} />
      )}
    </>
  );
}
