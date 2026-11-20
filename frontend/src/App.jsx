import { useCallback, useEffect, useState } from 'react';

const TOKEN_KEY = 'attendance_token';
const todayStr = () => new Date().toLocaleDateString('sv'); // yyyy-MM-dd

async function call(method, url, body, token) {
  const headers = {};
  if (body) headers['Content-Type'] = 'application/json';
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch(url, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `${method} ${url} failed (${res.status})`);
  return data;
}

/* ------------------------------------------------------------------ auth */

function AuthScreen({ onLogin }) {
  const [mode, setMode] = useState('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true); setError(null); setNotice(null);
    try {
      if (mode === 'register') {
        await call('POST', '/api/auth/register', { email, password });
        setNotice('Account created — an admin will approve it, then you can sign in.');
        setMode('login'); setPassword('');
      } else {
        onLogin(await call('POST', '/api/auth/login', { email, password }));
      }
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  };

  return (
    <div className="auth-page">
      <div className="auth-card">
        <h1>Attendance Portal</h1>
        <p className="muted">
          {mode === 'login'
            ? 'Sign in to see your punches'
            : 'Register — an admin links your account to your device profile'}
        </p>
        <form onSubmit={submit}>
          <input type="email" placeholder="Email" value={email}
                 onChange={(e) => setEmail(e.target.value)} required autoFocus />
          <input type="password" placeholder="Password (min 6 chars)" value={password}
                 onChange={(e) => setPassword(e.target.value)} required minLength={6} />
          <button className="primary block" disabled={busy}>
            {busy ? 'Working…' : mode === 'login' ? 'Sign in' : 'Create account'}
          </button>
        </form>
        {notice && <div className="notice">{notice}</div>}
        {error && <div className="error">{error}</div>}
        <button className="link" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(null); setNotice(null); }}>
          {mode === 'login' ? 'New here? Create an account' : 'Have an account? Sign in'}
        </button>
      </div>
    </div>
  );
}

/* -------------------------------------------------------------- punch UI */

function PunchTable({ punches, showName }) {
  if (!punches.length) return <p className="muted center">No punches yet.</p>;
  return (
    <table>
      <thead>
        <tr>
          {showName && <th>Employee</th>}
          <th>Time</th>
          <th>Mode</th>
        </tr>
      </thead>
      <tbody>
        {punches.map((p, i) => (
          <tr key={i}>
            {showName && <td>{p.name || `#${p.enrollNumber}`}</td>}
            <td>{p.time}</td>
            <td>{p.verifyLabel}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/* ------------------------------------------------------------- employee */

function EmployeeView({ me, onLogout }) {
  const [punches, setPunches] = useState([]);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    try {
      const d = await call('GET', '/api/attendance/my', null, me.token);
      setPunches(d.punches || []);
      setError(null);
    } catch (e) { setError(e.message); }
  }, [me.token]);

  useEffect(() => {
    load();
    const t = setInterval(load, 30000); // live-ish updates
    return () => clearInterval(t);
  }, [load]);

  const today = punches.filter((p) => p.time.startsWith(todayStr()));

  return (
    <div className="page">
      <header className="topbar">
        <div>
          <h1>Hi, {me.name || me.email}</h1>
          <span className="badge demo">employee{me.enrollNumber ? ` · device #${me.enrollNumber}` : ''}</span>
        </div>
        <button onClick={onLogout}>Sign out</button>
      </header>

      {error && <div className="error">{error}</div>}

      <section className="card">
        <h2>Today — {today.length} punch(es)</h2>
        <PunchTable punches={today} />
      </section>

      <section className="card">
        <h2>History</h2>
        <PunchTable punches={punches} />
      </section>
    </div>
  );
}

/* ---------------------------------------------------------------- admin */

function AdminView({ me, onLogout }) {
  const [tab, setTab] = useState('today');
  const [today, setToday] = useState([]);
  const [all, setAll] = useState([]);
  const [pending, setPending] = useState([]);
  const [unlinked, setUnlinked] = useState({});
  const [accounts, setAccounts] = useState([]);
  const [link, setLink] = useState({});
  const [notice, setNotice] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [t, a, p, acct] = await Promise.all([
        call('GET', '/api/attendance/today', null, me.token),
        call('GET', '/api/attendance/all', null, me.token),
        call('GET', '/api/admin/pending', null, me.token),
        call('GET', '/api/admin/accounts', null, me.token),
      ]);
      setToday(t.punches || []);
      setAll(a.punches || []);
      setPending(p.accounts || []);
      setUnlinked(p.deviceUsers || {});
      setAccounts(acct.accounts || []);
      setError(null);
    } catch (e) { setError(e.message); }
  }, [me.token]);

  useEffect(() => { load(); }, [load]);

  const act = async (fn) => {
    setBusy(true); setError(null);
    try { await fn(); await load(); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };

  const approve = (acct) => act(async () => {
    const enroll = Number(link[acct.id]);
    if (!enroll) throw new Error('pick a device user first');
    await call('POST', '/api/admin/approve', { accountId: acct.id, enrollNumber: enroll }, me.token);
  });

  const reject = (acct) => act(() =>
    call('POST', '/api/admin/reject', { accountId: acct.id }, me.token));

  const sync = () => act(async () => {
    const r = await call('POST', '/api/sync', {}, me.token);
    setNotice(`Synced ${r.appended} new punch(es)`);
    setTimeout(() => setNotice(null), 4000);
  });

  const uniqueToday = [...new Set(today.map((p) => p.enrollNumber))].length;

  return (
    <div className="page">
      <header className="topbar">
        <div>
          <h1>Attendance — Admin</h1>
          <span className="badge live">{me.email}</span>
        </div>
        <div>
          <button className="primary" onClick={sync} disabled={busy}>⇅ Sync device</button>
          <button onClick={onLogout}>Sign out</button>
        </div>
      </header>

      {notice && <div className="notice">{notice}</div>}
      {error && <div className="error">{error}</div>}

      <div className="stats">
        <div className="stat"><b>{uniqueToday}</b><span>present today</span></div>
        <div className="stat"><b>{today.length}</b><span>punches today</span></div>
        <div className="stat"><b>{pending.length}</b><span>awaiting approval</span></div>
      </div>

      <nav className="tabs">
        {[['today', 'Today'], ['all', 'All punches'], ['approvals', `Approvals${pending.length ? ` (${pending.length})` : ''}`], ['accounts', 'Accounts']]
          .map(([key, label]) => (
            <button key={key} className={tab === key ? 'tab active' : 'tab'} onClick={() => setTab(key)}>{label}</button>
          ))}
      </nav>

      {tab === 'today' && (
        <section className="card"><PunchTable punches={today} showName /></section>
      )}

      {tab === 'all' && (
        <section className="card"><PunchTable punches={all} showName /></section>
      )}

      {tab === 'approvals' && (
        <section className="card">
          {pending.length === 0 && <p className="muted center">No pending sign-ups.</p>}
          {pending.map((a) => (
            <div className="approval" key={a.id}>
              <span className="email">{a.email}</span>
              <select value={link[a.id] || ''} onChange={(e) => setLink({ ...link, [a.id]: e.target.value })}>
                <option value="">— link to device user —</option>
                {Object.entries(unlinked).map(([n, name]) => (
                  <option key={n} value={n}>{name} (#{n})</option>
                ))}
              </select>
              <button className="primary" disabled={busy || !link[a.id]} onClick={() => approve(a)}>Approve</button>
              <button className="danger" disabled={busy} onClick={() => reject(a)}>Reject</button>
            </div>
          ))}
        </section>
      )}

      {tab === 'accounts' && (
        <section className="card">
          <table>
            <thead><tr><th>Email</th><th>Role</th><th>Device user</th><th>Status</th></tr></thead>
            <tbody>
              {accounts.map((a) => (
                <tr key={a.id}>
                  <td>{a.email}</td>
                  <td>{a.role}</td>
                  <td>{a.name || (a.enrollNumber ? `#${a.enrollNumber}` : '—')}</td>
                  <td><span className={`badge ${a.status === 'active' ? 'live' : a.status === 'pending' ? 'warn' : 'demo'}`}>{a.status}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ app */

export default function App() {
  const [me, setMe] = useState(undefined); // undefined = restoring session
  const token = localStorage.getItem(TOKEN_KEY);

  useEffect(() => {
    (async () => {
      if (!token) { setMe(null); return; }
      try {
        const a = await call('GET', '/api/auth/me', null, token);
        setMe({ ...a, token });
      } catch {
        localStorage.removeItem(TOKEN_KEY);
        setMe(null);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const onLogin = (data) => {
    localStorage.setItem(TOKEN_KEY, data.token);
    setMe(data);
  };

  const logout = async () => {
    await call('POST', '/api/auth/logout', null, token).catch(() => {});
    localStorage.removeItem(TOKEN_KEY);
    setMe(null);
  };

  if (me === undefined) return <div className="page"><p className="muted">Loading…</p></div>;
  if (!me) return <AuthScreen onLogin={onLogin} />;

  if (me.status === 'pending') {
    return (
      <div className="auth-page"><div className="auth-card">
        <h1>Awaiting approval</h1>
        <p className="muted">Your account <b>{me.email}</b> is registered — an admin will link it to your device profile, then you can sign in.</p>
        <button onClick={logout}>Back to sign in</button>
      </div></div>
    );
  }
  if (me.status === 'rejected') {
    return (
      <div className="auth-page"><div className="auth-card">
        <h1>Account rejected</h1>
        <p className="muted">Contact your admin — <b>{me.email}</b> was not approved.</p>
        <button onClick={logout}>Back to sign in</button>
      </div></div>
    );
  }

  return me.role === 'admin'
    ? <AdminView me={me} onLogout={logout} />
    : <EmployeeView me={me} onLogout={logout} />;
}