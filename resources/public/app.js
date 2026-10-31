// Craft Agents Studio ES Module (CA-041)

export function connectStudioSocket(url) {
  const wsUrl = url || `ws://${window.location.host}/ws`;
  try {
    const ws = new WebSocket(wsUrl);
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        renderTelemetry(data);
      } catch (e) {
        console.error("Erro ao processar telemetria:", e);
      }
    };
    ws.onopen = () => console.log("[Studio] WebSocket conectado:", wsUrl);
    ws.onerror = (err) => console.warn("[Studio] WebSocket fallback para /api/status:", err);
    return ws;
  } catch (err) {
    console.warn("[Studio] Falha ao conectar WS, iniciando polling HTTP:", err);
    startPolling();
    return null;
  }
}

async function fetchStatus() {
  try {
    const res = await fetch("/api/status");
    if (res.ok) {
      const data = await res.json();
      renderTelemetry(data);
    }
  } catch (e) {
    console.warn("Polling status falhou:", e);
  }
}

function startPolling() {
  fetchStatus();
  setInterval(fetchStatus, 2000);
}

function renderTelemetry(data) {
  const root = document.getElementById("studio-root");
  if (!root) return;
  root.dataset.status = "connected";

  // Árvore BT
  const btEl = document.getElementById("bt-live-tree");
  if (btEl) {
    const agents = data.agents || {};
    const agentKeys = Object.keys(agents);
    btEl.innerHTML = `
      <h3>Agentes Ativos (${agentKeys.length})</h3>
      <div class="agent-cards">
        ${agentKeys.map(k => `
          <div class="agent-card">
            <h4>${k.toUpperCase()}</h4>
            <p><strong>Status:</strong> ${agents[k].status || "active"}</p>
            <p><strong>Pos:</strong> ${JSON.stringify(agents[k].pos || [])}</p>
          </div>
        `).join("")}
      </div>
    `;
  }

  // Leases
  const leasesEl = document.getElementById("actuator-leases");
  if (leasesEl) {
    const leases = data.leases || {};
    leasesEl.innerHTML = `
      <h3>Actuator Leases</h3>
      <div class="leases-grid">
        <span class="badge ${leases.legs ? "held" : "free"}">:legs: ${leases.legs?.owner || "livre"}</span>
        <span class="badge ${leases.hands ? "held" : "free"}">:hands: ${leases.hands?.owner || "livre"}</span>
        <span class="badge ${leases.gaze ? "held" : "free"}">:gaze: ${leases.gaze?.owner || "livre"}</span>
      </div>
    `;
  }

  // Radar A*
  const canvas = document.getElementById("astar-radar");
  if (canvas && canvas.getContext) {
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#0f172a";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.strokeStyle = "#38bdf8";
    ctx.lineWidth = 2;
    ctx.strokeRect(10, 10, canvas.width - 20, canvas.height - 20);

    ctx.fillStyle = "#94a3b8";
    ctx.font = "14px monospace";
    ctx.fillText("Radar 3D - PathSolver3D", 20, 35);
    ctx.fillText(`Tick do Servidor: ${data.tick || 0}`, 20, 60);

    // Centro (agente)
    ctx.fillStyle = "#22c55e";
    ctx.beginPath();
    ctx.arc(canvas.width / 2, canvas.height / 2, 8, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillText("Posição Central", canvas.width / 2 + 12, canvas.height / 2 + 4);
  }
}

// Inicializa no carregamento do módulo
if (typeof window !== "undefined") {
  window.addEventListener("DOMContentLoaded", () => {
    connectStudioSocket();
    startPolling();
  });
}
