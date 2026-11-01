import axios from 'axios';

/**
 * Axios instance for the backend API.
 *
 * - The access token lives only in memory (never localStorage), so a page reload
 *   performs a silent refresh using the httpOnly refresh cookie.
 * - On a 401, one refresh is attempted (shared by all concurrent requests, because the
 *   backend rotates refresh tokens and treats reuse as theft), then the request is retried once.
 */
const api = axios.create({
  baseURL: '/api/v1',
  timeout: 20000,
  withCredentials: true,
  headers: { 'X-Requested-With': 'netmon' },
});

let accessToken = null;
let refreshPromise = null;
let onSessionExpired = () => {};

export function setAccessToken(token) {
  accessToken = token;
}

export function getAccessToken() {
  return accessToken;
}

export function setSessionExpiredHandler(handler) {
  onSessionExpired = handler;
}

/** Calls /auth/refresh at most once at a time; concurrent callers share the result. */
export function refreshSession() {
  if (!refreshPromise) {
    refreshPromise = axios
      .post('/api/v1/auth/refresh', null, { withCredentials: true, headers: { 'X-Requested-With': 'netmon' } })
      .then((res) => {
        accessToken = res.data.accessToken;
        return res.data;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

api.interceptors.request.use((config) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

const NO_RETRY = ['/auth/login', '/auth/refresh', '/auth/logout', '/setup/'];

api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config;
    const status = error.response?.status;
    const url = original?.url || '';
    if (status === 401 && original && !original._retried && !NO_RETRY.some((p) => url.includes(p))) {
      original._retried = true;
      try {
        await refreshSession();
        original.headers.Authorization = `Bearer ${accessToken}`;
        return api(original);
      } catch {
        accessToken = null;
        onSessionExpired();
      }
    }
    return Promise.reject(error);
  },
);

/**
 * Turns any axios error into a readable message. With a translator `t`, known backend error
 * codes are shown in the user's language; otherwise the backend's English message is used.
 */
export function errorMessage(error, t) {
  const tr = (key, vars) => (t ? t(key, vars) : null);
  const data = error?.response?.data;
  if (data?.code || data?.message) {
    const translated = data.code ? tr(`errors.${data.code}`) : null;
    const base = translated && translated !== `errors.${data.code}` ? translated : data.message;
    const fields = data.fieldErrors?.map((f) => `${f.field}: ${f.message}`).join('; ');
    return fields ? `${base} (${fields})` : base;
  }
  if (error?.code === 'ECONNABORTED') return tr('errors.timeout') || 'The server did not respond in time.';
  if (!error?.response) {
    return tr('errors.network') || 'Cannot reach the server. Check your network connection or whether the backend is running.';
  }
  return tr('errors.status', { status: error.response.status }) || `Request failed with status ${error.response.status}.`;
}

export function errorRequestId(error) {
  return error?.response?.data?.requestId || error?.response?.headers?.['x-request-id'] || null;
}

export default api;
