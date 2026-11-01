import { useState } from 'react';
import { Outlet } from 'react-router-dom';
import Sidebar from './Sidebar.jsx';
import TopBar from './TopBar.jsx';

export default function Layout() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  return (
    <div className="nm-shell">
      <Sidebar open={sidebarOpen} onNavigate={() => setSidebarOpen(false)} />
      <div className="nm-main">
        <TopBar onToggleSidebar={() => setSidebarOpen((o) => !o)} />
        <main className="nm-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
