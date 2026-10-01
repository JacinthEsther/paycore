import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { CustomerLayout, PublicLayout, StaffLayout } from './components/Layout';
import { RequireAuth } from './components/ui';
import { AddMoney } from './pages/app/AddMoney';
import { BankHome } from './pages/app/BankHome';
import { Profile } from './pages/app/Profile';
import { Transactions, TransactionReceipt } from './pages/app/Transactions';
import { Transfer } from './pages/app/Transfer';
import { Customers } from './pages/admin/Customers';
import { Ledger } from './pages/admin/Ledger';
import { Operations } from './pages/admin/Operations';
import { ReviewQueue } from './pages/admin/ReviewQueue';
import { Home } from './pages/Home';
import { Kyc } from './pages/Kyc';
import { Login } from './pages/Login';
import { Register } from './pages/Register';
import { Security } from './pages/Security';
import './styles.css';

/** Compliance lands on KYC reviews; operations officers on their approvals queue. */
function StaffHome() {
  const { can } = useAuth();
  return can('KYC_REVIEW') ? <ReviewQueue /> : <Navigate to="/admin/operations" replace />;
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<PublicLayout />}>
            <Route index element={<Home />} />
            <Route path="register" element={<Register />} />
            <Route path="login" element={<Login />} />
          </Route>

          <Route
            path="app"
            element={
              <RequireAuth>
                <CustomerLayout />
              </RequireAuth>
            }
          >
            <Route index element={<BankHome />} />
            <Route path="transfer" element={<Transfer />} />
            <Route path="add-money" element={<AddMoney />} />
            <Route path="transactions" element={<Transactions />} />
            <Route path="transactions/:transactionId" element={<TransactionReceipt />} />
            <Route path="profile" element={<Profile />} />
            <Route path="kyc" element={<Kyc />} />
            <Route path="security" element={<Security />} />
            <Route path="accounts/*" element={<Navigate to="/app" replace />} />
          </Route>

          <Route
            path="admin"
            element={
              <RequireAuth staff>
                <StaffLayout />
              </RequireAuth>
            }
          >
            <Route index element={<StaffHome />} />
            <Route path="customers" element={<Customers />} />
            <Route path="operations" element={<Operations />} />
            <Route path="ledger" element={<Ledger />} />
          </Route>

          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </StrictMode>,
);
