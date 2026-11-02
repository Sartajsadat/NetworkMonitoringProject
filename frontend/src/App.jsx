import { Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout.jsx';
import ProtectedRoute from './auth/ProtectedRoute.jsx';
import { PERMISSIONS, ROLES } from './auth/roles.js';
import LoginPage from './pages/LoginPage.jsx';
import SetupPage from './pages/SetupPage.jsx';
import DashboardPage from './pages/DashboardPage.jsx';
import ServersPage from './pages/ServersPage.jsx';
import ServerDetailPage from './pages/ServerDetailPage.jsx';
import ServerFormPage from './pages/ServerFormPage.jsx';
import ServerGroupsPage from './pages/ServerGroupsPage.jsx';
import UsersPage from './pages/UsersPage.jsx';
import AuditLogPage from './pages/AuditLogPage.jsx';
import ChangePasswordPage from './pages/ChangePasswordPage.jsx';
import NotFoundPage from './pages/NotFoundPage.jsx';
import AgentsPage from './pages/monitoring/AgentsPage.jsx';
import CpuMemoryPage from './pages/monitoring/CpuMemoryPage.jsx';
import DisksPage from './pages/monitoring/DisksPage.jsx';
import InterfacesPage from './pages/monitoring/InterfacesPage.jsx';
import ServicesPage from './pages/monitoring/ServicesPage.jsx';
import AnalyzerPage from './pages/captures/AnalyzerPage.jsx';
import CaptureDetailPage from './pages/captures/CaptureDetailPage.jsx';
import CapturesPage from './pages/captures/CapturesPage.jsx';
import NewCapturePage from './pages/captures/NewCapturePage.jsx';
import TimelinePage from './pages/events/TimelinePage.jsx';
import EventDetailPage from './pages/events/EventDetailPage.jsx';
import AlertsPage from './pages/alerts/AlertsPage.jsx';
import AlertDetailPage from './pages/alerts/AlertDetailPage.jsx';
import AlertRulesPage from './pages/alerts/AlertRulesPage.jsx';
import SubnetsPage from './pages/network/SubnetsPage.jsx';
import IpAddressesPage from './pages/network/IpAddressesPage.jsx';
import IpDetailPage from './pages/network/IpDetailPage.jsx';
import DevicesPage from './pages/network/DevicesPage.jsx';
import TrafficPage from './pages/network/TrafficPage.jsx';
import FirewallIntegrationsPage from './pages/firewall/FirewallIntegrationsPage.jsx';
import FirewallRulesPage from './pages/firewall/FirewallRulesPage.jsx';
import RuleRequestPage from './pages/firewall/RuleRequestPage.jsx';
import RuleDetailPage from './pages/firewall/RuleDetailPage.jsx';
import AppAccessPage from './pages/firewall/AppAccessPage.jsx';
import AppDetailPage from './pages/firewall/AppDetailPage.jsx';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/setup" element={<SetupPage />} />
      <Route
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      >
        <Route index element={<DashboardPage />} />
        <Route path="servers" element={<ServersPage />} />
        <Route
          path="servers/new"
          element={<ProtectedRoute roles={PERMISSIONS.manageServers}><ServerFormPage /></ProtectedRoute>}
        />
        <Route path="servers/:id" element={<ServerDetailPage />} />
        <Route
          path="servers/:id/edit"
          element={<ProtectedRoute roles={PERMISSIONS.manageServers}><ServerFormPage /></ProtectedRoute>}
        />
        <Route path="server-groups" element={<ServerGroupsPage />} />
        <Route path="agents" element={<AgentsPage />} />
        <Route path="monitoring/cpu-memory" element={<CpuMemoryPage />} />
        <Route path="monitoring/disks" element={<DisksPage />} />
        <Route path="monitoring/interfaces" element={<InterfacesPage />} />
        <Route path="monitoring/services" element={<ServicesPage />} />
        <Route path="captures" element={<ProtectedRoute roles={PERMISSIONS.viewCaptures}><CapturesPage /></ProtectedRoute>} />
        <Route path="captures/new" element={<ProtectedRoute roles={PERMISSIONS.manageCaptures}><NewCapturePage /></ProtectedRoute>} />
        <Route path="captures/:id" element={<ProtectedRoute roles={PERMISSIONS.viewCaptures}><CaptureDetailPage /></ProtectedRoute>} />
        <Route path="analyzer" element={<ProtectedRoute roles={PERMISSIONS.viewCaptures}><AnalyzerPage /></ProtectedRoute>} />
        <Route path="timeline" element={<TimelinePage />} />
        <Route path="events/:id" element={<EventDetailPage />} />
        <Route path="alerts" element={<AlertsPage />} />
        <Route path="alerts/rules" element={<AlertRulesPage />} />
        <Route path="alerts/:id" element={<AlertDetailPage />} />
        <Route path="subnets" element={<SubnetsPage />} />
        <Route path="ip-addresses" element={<IpAddressesPage />} />
        <Route path="ip-addresses/:id" element={<IpDetailPage />} />
        <Route path="network-devices" element={<DevicesPage />} />
        <Route path="traffic" element={<ProtectedRoute roles={PERMISSIONS.viewTraffic}><TrafficPage /></ProtectedRoute>} />
        <Route path="firewall" element={<FirewallIntegrationsPage />} />
        <Route path="ip-policies" element={<FirewallRulesPage />} />
        <Route path="ip-policies/new" element={<ProtectedRoute roles={PERMISSIONS.requestFirewallRules}><RuleRequestPage /></ProtectedRoute>} />
        <Route path="ip-policies/:id" element={<RuleDetailPage />} />
        <Route path="app-access" element={<AppAccessPage />} />
        <Route path="app-access/:id" element={<AppDetailPage />} />
        <Route path="users" element={<ProtectedRoute roles={[ROLES.SUPER_ADMIN]}><UsersPage /></ProtectedRoute>} />
        <Route path="audit-logs" element={<ProtectedRoute roles={PERMISSIONS.viewAudit}><AuditLogPage /></ProtectedRoute>} />
        <Route path="account/password" element={<ChangePasswordPage />} />
        <Route path="dashboard" element={<Navigate to="/" replace />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
