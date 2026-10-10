import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { CustomerLayout, DeskGate, PublicLayout, StaffLayout } from './components/Layout';
import { RequireAuth } from './components/ui';
import { AddMoney } from './pages/app/AddMoney';
import { BankHome } from './pages/app/BankHome';
import { Bills } from './pages/app/Bills';
import { Limits } from './pages/app/Limits';
import { PinAndDevices } from './pages/app/PinAndDevices';
import { Profile } from './pages/app/Profile';
import { Transactions, TransactionReceipt } from './pages/app/Transactions';
import { Transfer } from './pages/app/Transfer';
import { Customers } from './pages/admin/Customers';
import { Ledger } from './pages/admin/Ledger';
import { MyDesk } from './pages/admin/MyDesk';
import { Operations } from './pages/admin/Operations';
import { Payments } from './pages/admin/Payments';
import { Reconciliation } from './pages/admin/Reconciliation';
import { ReviewQueue } from './pages/admin/ReviewQueue';
import { Scenarios } from './pages/admin/Scenarios';
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
            <Route path="bills" element={<Bills />} />
            <Route path="transactions" element={<Transactions />} />
            <Route path="transactions/:transactionId" element={<TransactionReceipt />} />
            <Route path="profile" element={<Profile />} />
            <Route path="devices" element={<PinAndDevices />} />
            <Route path="limits" element={<Limits />} />
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
            <Route index element={<MyDesk />} />
            <Route path="kyc" element={<DeskGate to="/admin/kyc"><ReviewQueue /></DeskGate>} />
            <Route path="customers" element={<DeskGate to="/admin/customers"><Customers /></DeskGate>} />
            <Route path="operations" element={<DeskGate to="/admin/operations"><Operations /></DeskGate>} />
            <Route path="ledger" element={<DeskGate to="/admin/ledger"><Ledger /></DeskGate>} />
            <Route path="payments" element={<DeskGate to="/admin/payments"><Payments /></DeskGate>} />
            <Route path="reconciliation" element={<DeskGate to="/admin/reconciliation"><Reconciliation /></DeskGate>} />
            <Route path="scenarios" element={<Scenarios />} />
          </Route>

          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </StrictMode>,
);
