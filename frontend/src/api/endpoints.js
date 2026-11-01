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
