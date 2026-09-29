import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { RequireAuth } from './components/ui';
import { Customers } from './pages/admin/Customers';
import { ReviewQueue } from './pages/admin/ReviewQueue';
import { Dashboard } from './pages/Dashboard';
import { Home } from './pages/Home';
import { Kyc } from './pages/Kyc';
import { Login } from './pages/Login';
import { Register } from './pages/Register';
import { Security } from './pages/Security';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<Home />} />
            <Route path="register" element={<Register />} />
            <Route path="login" element={<Login />} />
            <Route path="app" element={<RequireAuth><Dashboard /></RequireAuth>} />
            <Route path="app/kyc" element={<RequireAuth><Kyc /></RequireAuth>} />
            <Route path="app/security" element={<RequireAuth><Security /></RequireAuth>} />
            <Route path="admin" element={<RequireAuth admin><ReviewQueue /></RequireAuth>} />
            <Route path="admin/customers" element={<RequireAuth admin><Customers /></RequireAuth>} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </StrictMode>,
);
