import { useCallback, useEffect, useState } from 'react';

const pad = (row, n) => [...row, ...Array(Math.max(0, n - row.length)).fill('')];

async function call(method, url, body) {
  const res = await fetch(url, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `${method} ${url} failed (${res.status})`);
  return data;
}

export default function App() {
  const [rows, setRows] = useState([]);
  const [mode, setMode] = useState('…');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [editingRow, setEditingRow] = useState(null); // sheet row number (1-based)
  const [draft, setDraft] = useState([]);
  const [newRow, setNewRow] = useState([]);
  const [notice, setNotice] = useState(null);

  const refresh = useCallback(async () => {
    try {
      const [status, data] = await Promise.all([
        call('GET', '/api/status'),
        call('GET', '/api/rows'),
      ]);
      setMode(status.mode);
      setRows(data.values || []);
      setError(null);
    } catch (e) {
      setError(`Backend unreachable: ${e.message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const header = rows[0] || [];
  const dataRows = rows.slice(1);
  const colCount = Math.max(header.length, 1);

  useEffect(() => {
    setNewRow(Array(colCount).fill(''));
  }, [colCount]);

  const run = async (fn) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
      await refresh();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const addRow = () =>
    run(async () => {
      await call('POST', '/api/rows', { values: newRow });
      setNewRow(Array(colCount).fill(''));
    });

  const saveRow = (rowNumber) =>
    run(async () => {
      await call('PUT', `/api/rows/${rowNumber}`, { values: draft });
      setEditingRow(null);
    });

  const deleteRow = (rowNumber) =>
    run(() => call('DELETE', `/api/rows/${rowNumber}`));

  const syncDevice = () =>
    run(async () => {
      setNotice(null);
      const res = await call('POST', '/api/sync');
      setNotice(`Synced ${res.appended} punch(es) — watermark ${res.watermark ?? 'n/a'}`);
    });

  const startEdit = (idx) => {
    setEditingRow(idx + 2); // sheet row number: +1 for header, +1 for 1-based
    setDraft(pad(dataRows[idx], colCount).map(String));
  };

  if (loading) return <div className="page"><p className="muted">Loading…</p></div>;

  return (
    <div className="page">
      <header className="topbar">
        <div>
          <h1>Attendance — Sheet Editor</h1>
          <span className={`badge ${mode.includes('Google') ? 'live' : 'demo'}`}>
            {mode}
          </span>
        </div>
        <div>
          <button className="primary" onClick={syncDevice} disabled={busy}>⇅ Sync device</button>
          <button onClick={refresh} disabled={busy}>↻ Refresh</button>
        </div>
      </header>

      {notice && <div className="notice" role="status">{notice}</div>}
      {error && <div className="error" role="alert">{error}</div>}

      <table>
        <thead>
          <tr>
            <th className="rownum">#</th>
            {pad(header, colCount).map((h, i) => <th key={i}>{h || `Col ${i + 1}`}</th>)}
            <th className="actions">Actions</th>
          </tr>
        </thead>
        <tbody>
          {dataRows.map((row, i) => {
            const rowNumber = i + 2;
            const editing = editingRow === rowNumber;
            return (
              <tr key={rowNumber} className={editing ? 'editing' : ''}>
                <td className="rownum">{rowNumber}</td>
                {pad(row, colCount).map((cell, c) => (
                  <td key={c}>
                    {editing ? (
                      <input
                        value={draft[c]}
                        onChange={(e) =>
                          setDraft(draft.map((d, j) => (j === c ? e.target.value : d)))
                        }
                      />
                    ) : (
                      String(cell)
                    )}
                  </td>
                ))}
                <td className="actions">
                  {editing ? (
                    <>
                      <button className="primary" disabled={busy} onClick={() => saveRow(rowNumber)}>Save</button>
                      <button disabled={busy} onClick={() => setEditingRow(null)}>Cancel</button>
                    </>
                  ) : (
                    <>
                      <button disabled={busy} onClick={() => startEdit(i)}>Edit</button>
                      <button className="danger" disabled={busy} onClick={() => deleteRow(rowNumber)}>Delete</button>
                    </>
                  )}
                </td>
              </tr>
            );
          })}
          {dataRows.length === 0 && (
            <tr><td colSpan={colCount + 2} className="muted center">No data rows yet.</td></tr>
          )}
        </tbody>
      </table>

      <section className="add-row">
        <h2>Add row</h2>
        <div className="add-form">
          {newRow.map((v, i) => (
            <input
              key={i}
              placeholder={header[i] || `Col ${i + 1}`}
              value={v}
              onChange={(e) =>
                setNewRow(newRow.map((x, j) => (j === i ? e.target.value : x)))
              }
            />
          ))}
          <button className="primary" disabled={busy || newRow.every((v) => v === '')} onClick={addRow}>
            + Append
          </button>
        </div>
      </section>
    </div>
  );
}
