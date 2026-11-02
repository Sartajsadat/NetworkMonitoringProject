import api from './client.js';

// Auth & setup
export const authApi = {
  login: (username, password) => api.post('/auth/login', { username, password }).then((r) => r.data),
  logout: () => api.post('/auth/logout'),
  me: () => api.get('/auth/me').then((r) => r.data),
  changePassword: (currentPassword, newPassword) => api.post('/auth/password', { currentPassword, newPassword }),
};

export const setupApi = {
  status: () => api.get('/setup/status').then((r) => r.data),
  createAdmin: (body) => api.post('/setup/admin', body).then((r) => r.data),
};

// Users & roles
export const userApi = {
  search: (params) => api.get('/users', { params }).then((r) => r.data),
  get: (id) => api.get(`/users/${id}`).then((r) => r.data),
  create: (body) => api.post('/users', body).then((r) => r.data),
  update: (id, body) => api.put(`/users/${id}`, body).then((r) => r.data),
  activate: (id) => api.post(`/users/${id}/activate`).then((r) => r.data),
  deactivate: (id) => api.post(`/users/${id}/deactivate`).then((r) => r.data),
  unlock: (id) => api.post(`/users/${id}/unlock`).then((r) => r.data),
  resetPassword: (id, newPassword) => api.post(`/users/${id}/reset-password`, { newPassword }),
  roles: () => api.get('/roles').then((r) => r.data),
};

// Servers & groups
export const serverApi = {
  search: (params) => api.get('/servers', { params }).then((r) => r.data),
  get: (id) => api.get(`/servers/${id}`).then((r) => r.data),
  create: (body) => api.post('/servers', body).then((r) => r.data),
  update: (id, body) => api.put(`/servers/${id}`, body).then((r) => r.data),
  remove: (id) => api.delete(`/servers/${id}`),
  organizations: () => api.get('/servers/organizations').then((r) => r.data),
};

export const groupApi = {
  list: () => api.get('/server-groups').then((r) => r.data),
  create: (body) => api.post('/server-groups', body).then((r) => r.data),
  update: (id, body) => api.put(`/server-groups/${id}`, body).then((r) => r.data),
  remove: (id) => api.delete(`/server-groups/${id}`),
};

// Audit & dashboard
export const auditApi = {
  search: (params) => api.get('/audit-logs', { params }).then((r) => r.data),
  actions: () => api.get('/audit-logs/actions').then((r) => r.data),
};

export const dashboardApi = {
  summary: () => api.get('/dashboard/summary').then((r) => r.data),
};

// Agents (Phase 2)
export const agentApi = {
  list: () => api.get('/agents').then((r) => r.data),
  forServer: (serverId) => api.get(`/servers/${serverId}/agent`).then((r) => r.data),
  createToken: (serverId) => api.post(`/servers/${serverId}/agent/enrollment-token`).then((r) => r.data),
  revoke: (serverId) => api.post(`/servers/${serverId}/agent/revoke`).then((r) => r.data),
  updateConfig: (serverId, body) => api.put(`/servers/${serverId}/agent/config`, body).then((r) => r.data),
};

// Metrics (Phase 2)
export const metricsApi = {
  history: (serverId, { metrics, resource, from, to }) =>
    api.get(`/servers/${serverId}/metrics`, {
      params: { metric: metrics, resource: resource || undefined, from, to },
      paramsSerializer: { indexes: null }, // metric=a&metric=b
    }).then((r) => r.data),
  latest: (serverId) => api.get(`/servers/${serverId}/metrics/latest`).then((r) => r.data),
  processes: (serverId) => api.get(`/servers/${serverId}/processes`).then((r) => r.data),
  fleet: () => api.get('/monitoring/fleet').then((r) => r.data),
};

// Packet captures (Phase 4)
export const captureApi = {
  list: (params) => api.get('/captures', { params }).then((r) => r.data),
  limits: () => api.get('/captures/limits').then((r) => r.data),
  capabilities: () => api.get('/captures/capabilities').then((r) => r.data),
  create: (body) => api.post('/captures', body).then((r) => r.data),
  get: (id) => api.get(`/captures/${id}`).then((r) => r.data),
  stop: (id) => api.post(`/captures/${id}/stop`).then((r) => r.data),
  packets: (id, params) => api.get(`/captures/${id}/packets`, { params }).then((r) => r.data),
  packet: (id, frame) => api.get(`/captures/${id}/packets/${frame}`).then((r) => r.data),
  bytes: (id, frame) => api.get(`/captures/${id}/packets/${frame}/bytes`).then((r) => r.data),
  statistics: (id) => api.get(`/captures/${id}/statistics`).then((r) => r.data),
  validateFilter: (filter) => api.post('/captures/display-filter/validate', { filter }).then((r) => r.data),
  downloadTicket: (id) => api.post(`/captures/${id}/download-ticket`).then((r) => r.data),
  deleteFiles: (id, reason) => api.delete(`/captures/${id}/files`, { data: { reason } }).then((r) => r.data),
};

// Events and alerts (Phase 5)
const multi = { paramsSerializer: { indexes: null } }; // category=A&category=B
export const eventApi = {
  search: (params) => api.get('/events', { params, ...multi }).then((r) => r.data),
  histogram: (params) => api.get('/events/histogram', { params, ...multi }).then((r) => r.data),
  get: (id) => api.get(`/events/${id}`).then((r) => r.data),
  context: (id) => api.get(`/events/${id}/context`).then((r) => r.data),
};

export const alertApi = {
  list: (params) => api.get('/alerts', { params }).then((r) => r.data),
  counts: () => api.get('/alerts/counts').then((r) => r.data),
  get: (id) => api.get(`/alerts/${id}`).then((r) => r.data),
  acknowledge: (id, comment) => api.post(`/alerts/${id}/acknowledge`, { comment }).then((r) => r.data),
  resolve: (id, comment) => api.post(`/alerts/${id}/resolve`, { comment }).then((r) => r.data),
  rules: () => api.get('/alert-rules').then((r) => r.data),
  ruleTypes: () => api.get('/alert-rules/types').then((r) => r.data),
  createRule: (body) => api.post('/alert-rules', body).then((r) => r.data),
  updateRule: (id, body) => api.put(`/alert-rules/${id}`, body).then((r) => r.data),
  deleteRule: (id) => api.delete(`/alert-rules/${id}`),
};

// IP inventory and traffic (Phase 6)
export const networkApi = {
  subnets: () => api.get('/subnets').then((r) => r.data),
  createSubnet: (body) => api.post('/subnets', body).then((r) => r.data),
  updateSubnet: (id, body) => api.put(`/subnets/${id}`, body).then((r) => r.data),
  deleteSubnet: (id) => api.delete(`/subnets/${id}`),
  ips: (params) => api.get('/ip-addresses', { params }).then((r) => r.data),
  ip: (id) => api.get(`/ip-addresses/${id}`).then((r) => r.data),
  createIp: (body) => api.post('/ip-addresses', body).then((r) => r.data),
  updateIp: (id, body) => api.put(`/ip-addresses/${id}`, body).then((r) => r.data),
  deleteIp: (id) => api.delete(`/ip-addresses/${id}`),
  importIps: (csv) => api.post('/ip-addresses/import', csv, { headers: { 'Content-Type': 'text/plain' } }).then((r) => r.data),
  devices: () => api.get('/network-devices').then((r) => r.data),
  createDevice: (body) => api.post('/network-devices', body).then((r) => r.data),
  updateDevice: (id, body) => api.put(`/network-devices/${id}`, body).then((r) => r.data),
  deleteDevice: (id) => api.delete(`/network-devices/${id}`),
  flowSummary: (params) => api.get('/network-flows/summary', { params }).then((r) => r.data),
  flows: (params) => api.get('/network-flows', { params }).then((r) => r.data),
  collector: () => api.get('/network-flows/collector').then((r) => r.data),
};
