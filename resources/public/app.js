// Craft Agents Studio — Voyager 2.0 Native JVM Frontend (CA-040 / CA-041)

let wsInstance = null;
let lastEventsJson = "";
let lastSkillsJson = "";
let lastAgentsJson = "";
let lastLeasesJson = "";

export function connectStudioSocket(url) {
  const host = window.location.host || "localhost:8095";
  const wsUrl = url || `ws://${host}/ws`;
  try {
    const ws = new WebSocket(wsUrl);
    ws.onopen = () => {
      console.log("[Studio] WebSocket conectado:", wsUrl);
      updateStatusBadge(true);
    };
    ws.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        renderTelemetry(data);
      } catch (e) {
        console.error("Erro ao processar telemetria WS:", e);
      }
    };
    ws.onerror = (err) => {
      console.warn("[Studio] WebSocket error, fallback para polling HTTP:", err);
      updateStatusBadge(false);
    };
    ws.onclose = () => {
      console.warn("[Studio] WebSocket fechado, tentando reconectar em 3s...");
      updateStatusBadge(false);
      setTimeout(() => connectStudioSocket(wsUrl), 3000);
    };
    wsInstance = ws;
    return ws;
  } catch (err) {
    console.warn("[Studio] Falha ao criar WebSocket, iniciando polling:", err);
    updateStatusBadge(false);
    return null;
  }
}

function updateStatusBadge(connected) {
  const badge = document.getElementById("conn-status");
  if (!badge) return;
  if (connected) {
    badge.innerHTML = `<span class="dot"></span> CONECTADO (20Hz)`;
    badge.style.background = "#14532d44";
    badge.style.color = "#4ade80";
    badge.style.borderColor = "#22c55e55";
  } else {
    badge.innerHTML = `<span class="dot" style="background:#f59e0b;box-shadow:0 0 8px #f59e0b"></span> POLLING HTTP`;
    badge.style.background = "#78350f44";
    badge.style.color = "#fbbf24";
    badge.style.borderColor = "#f59e0b55";
  }
}

async function fetchStatus() {
  try {
    const res = await fetch("/api/status");
    if (res.ok) {
      const data = await res.json();
      renderTelemetry(data);
      updateStatusBadge(true);
    }
  } catch (e) {
    console.warn("[Studio] Polling HTTP falhou:", e);
    updateStatusBadge(false);
  }
}

export async function sendChatMessage(message, speaker = "pace") {
  if (!message || !message.trim()) return;
  try {
    const res = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ speaker: speaker, message: message.trim() })
    });
    if (res.ok) {
      console.log("[Studio] Comando enviado com sucesso:", message);
      setTimeout(fetchStatus, 150);
    }
  } catch (e) {
    console.error("[Studio] Erro ao enviar comando:", e);
  }
}

export async function clearCognitiveFeed() {
  try {
    const res = await fetch("/api/clear-events", { method: "POST" });
    if (res.ok) {
      lastEventsJson = "";
      const logsEl = document.getElementById("cognitive-logs");
      if (logsEl) {
        logsEl.innerHTML = `<p style="color:#94a3b8">Feed limpo. Aguardando novos eventos cognitivos...</p>`;
      }
      setTimeout(fetchStatus, 150);
    }
  } catch (e) {
    console.error("[Studio] Erro ao limpar feed:", e);
  }
}

function formatEventTime(evt) {
  const ts = evt.timestamp;
  if (!ts) return evt.time || "";
  const d = new Date(ts);
  const timeStr = d.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  const diffSec = Math.floor((Date.now() - ts) / 1000);
  if (diffSec < 10) return `${timeStr} (agora mesmo)`;
  if (diffSec < 60) return `${timeStr} (há ${diffSec}s)`;
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${timeStr} (há ${diffMin}m)`;
  return timeStr;
}

function renderTelemetry(data) {
  const root = document.getElementById("studio-root");
  if (!root) return;
  root.dataset.status = "connected";

  // Server Tick
  const tickEl = document.getElementById("server-tick");
  if (tickEl && data.tick !== undefined) {
    tickEl.textContent = `Tick: ${data.tick}`;
  }

  // 1. Agentes (renderiza apenas se houve mudança)
  const agentsJson = JSON.stringify(data.agents || {});
  if (agentsJson !== lastAgentsJson) {
    lastAgentsJson = agentsJson;
    const agentsContainer = document.getElementById("agent-cards-container");
    if (agentsContainer) {
      const agents = data.agents || {};
      const agentKeys = Object.keys(agents);
      if (agentKeys.length === 0) {
        agentsContainer.innerHTML = `<p style="color:#94a3b8">Nenhum agente ativo registrado.</p>`;
      } else {
        agentsContainer.innerHTML = agentKeys.map(k => {
          const ag = agents[k];
          const posStr = ag.pos ? `[${ag.pos.map(n => Number(n).toFixed(1)).join(", ")}]` : "N/A";
          return `
            <div class="agent-card">
              <div class="agent-card-header">
                <span class="agent-name">${k.toUpperCase()}</span>
                <span class="agent-pos">${posStr}</span>
              </div>
              <div class="agent-task"><strong>Status:</strong> ${ag.status || "ativo"}</div>
              ${ag.task ? `<div class="agent-task"><strong>Tarefa:</strong> ${ag.task}</div>` : ''}
              ${ag.last_learned ? `<div class="agent-task"><strong>Última Skill:</strong> <span style="color:#fbbf24">${ag.last_learned}</span></div>` : ''}
              ${ag.skills && ag.skills.length ? `
                <div class="agent-skills-tag">🧠 Skills: ${ag.skills.join(", ")}</div>
              ` : ''}
            </div>
          `;
        }).join("");
      }
    }
  }

  // 2. Leases & Recursos Espaciais Compartilhados (renderiza apenas se houve mudança)
  const leasesJson = JSON.stringify({leases: data.leases || {}, resources: data.resources || {}});
  if (leasesJson !== lastLeasesJson) {
    lastLeasesJson = leasesJson;
    const leasesContainer = document.getElementById("leases-container");
    if (leasesContainer) {
      const leases = data.leases || {};
      const resources = data.resources || {};
      const resKeys = Object.keys(resources);
      const resHtml = resKeys.length ? resKeys.map(rk => {
        const r = resources[rk];
        return `<span class="badge held" style="background:#0284c7">🔒 ${rk}: ${r.holder || "ocupado"}</span>`;
      }).join(" ") : '';
      leasesContainer.innerHTML = `
        <span class="badge ${leases.legs ? "held" : "free"}">:legs: ${leases.legs?.owner || "livre"}</span>
        <span class="badge ${leases.hands ? "held" : "free"}">:hands: ${leases.hands?.owner || "livre"}</span>
        <span class="badge ${leases.gaze ? "held" : "free"}">:gaze: ${leases.gaze?.owner || "livre"}</span>
        ${resHtml}
      `;
    }
  }

  // 3. Voyager Skill Library (renderiza apenas se houve mudança)
  const skillsJson = JSON.stringify(data.skills || []);
  if (skillsJson !== lastSkillsJson) {
    lastSkillsJson = skillsJson;
    const skillsListEl = document.getElementById("skills-list");
    if (skillsListEl && data.skills) {
      if (data.skills.length === 0) {
        skillsListEl.innerHTML = `<p style="color:#94a3b8">Nenhuma skill na Skill Library ainda.</p>`;
      } else {
        skillsListEl.innerHTML = data.skills.map(s => `
          <div class="skill-card">
            <div class="skill-header">
              <span class="skill-title">⚡ :${s.id}</span>
              <span class="badge badge-success">Malli AST Válida</span>
            </div>
            <div class="skill-meta">
              <span><strong>Nome:</strong> ${s.name}</span>
              <span><strong>Gatilho:</strong> <code>${s.trigger}</code></span>
              <span><strong>Timeout:</strong> ${s.timeout_ms || 20000}ms</span>
            </div>
            <pre class="skill-code">${escapeHtml(s.edn || JSON.stringify(s.tree, null, 2))}</pre>
          </div>
        `).join("");
      }
    }
  }

  // 4. Feed Cognitivo em Tempo Real (SEM PISCAR / diff-checked)
  const eventsJson = JSON.stringify(data.events || []);
  if (eventsJson !== lastEventsJson) {
    lastEventsJson = eventsJson;
    const logsEl = document.getElementById("cognitive-logs");
    if (logsEl && data.events) {
      const events = data.events;
      if (events.length === 0) {
        logsEl.innerHTML = `<p style="color:#94a3b8">Aguardando eventos cognitivos do servidor...</p>`;
      } else {
        logsEl.innerHTML = events.map(evt => {
          const borderCol = evt.color || "#38bdf8";
          const formattedTime = formatEventTime(evt);
          return `
            <div class="feed-item" style="border-left-color: ${borderCol}">
              <div class="feed-header">
                <span class="feed-tag" style="background:${borderCol}22; color:${borderCol}">${evt.badge || evt.type || "INFO"}</span>
                <span class="feed-time">${formattedTime}</span>
              </div>
              <div class="feed-source">${evt.source || "Sistema"}</div>
              <div class="feed-text">${escapeHtml(evt.text || "")}</div>
            </div>
          `;
        }).join("");

        // Auto-scroll para o fim
        logsEl.scrollTop = logsEl.scrollHeight;
      }
    }
  }

  // 5. Radar 3D Canvas
  renderRadar(data);
}

function escapeHtml(str) {
  if (!str) return "";
  return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function renderRadar(data) {
  const canvas = document.getElementById("astar-radar");
  if (!canvas || !canvas.getContext) return;
  const ctx = canvas.getContext("2d");
  const w = canvas.width;
  const h = canvas.height;

  // Background
  ctx.fillStyle = "#070b14";
  ctx.fillRect(0, 0, w, h);

  // Grid
  ctx.strokeStyle = "#131f33";
  ctx.lineWidth = 1;
  const step = 32;
  for (let x = 0; x < w; x += step) {
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(x, h);
    ctx.stroke();
  }
  for (let y = 0; y < h; y += step) {
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(w, y);
    ctx.stroke();
  }

  // Centro de Referência do Santuário (-175, 178)
  const centerWorldX = -175.0;
  const centerWorldZ = 178.0;
  const scale = 3.8; // pixels por bloco

  const toScreenX = (wx) => (w / 2) + (wx - centerWorldX) * scale;
  const toScreenY = (wz) => (h / 2) + (wz - centerWorldZ) * scale;

  // 1. Lago Norte (Área aberta de fuga e resgate)
  const lakeX1 = toScreenX(-205);
  const lakeY1 = toScreenY(148);
  const lakeW = ((-145) - (-205)) * scale;
  const lakeH = (163 - 148) * scale;
  ctx.fillStyle = "rgba(14, 165, 233, 0.08)";
  ctx.strokeStyle = "rgba(14, 165, 233, 0.4)";
  ctx.setLineDash([4, 4]);
  ctx.lineWidth = 1;
  ctx.fillRect(lakeX1, lakeY1, lakeW, lakeH);
  ctx.strokeRect(lakeX1, lakeY1, lakeW, lakeH);
  ctx.setLineDash([]);
  ctx.fillStyle = "#38bdf8";
  ctx.font = "10px monospace";
  ctx.fillText("🌊 Lago Norte (Área Externa)", lakeX1 + 6, lakeY1 + 12);

  // 2. Corredor Central de Trânsito A*
  const corrX = toScreenX(-179);
  const corrY = toScreenY(165);
  const corrW = ((-171) - (-179)) * scale;
  const corrH = (196 - 165) * scale;
  ctx.fillStyle = "rgba(51, 65, 85, 0.25)";
  ctx.strokeStyle = "#334155";
  ctx.lineWidth = 1;
  ctx.fillRect(corrX, corrY, corrW, corrH);
  ctx.strokeRect(corrX, corrY, corrW, corrH);

  // 3. As 4 Jaulas Individuais do Santuário
  const enclosures = [
    {
      id: "pen-north-west",
      name: "❄️ Tundra (NW)",
      species: "Mamute/Raposa",
      minX: -214, maxX: -180, minZ: 165, maxZ: 177,
      color: "#0ea5e9",
      bg: "rgba(14, 165, 233, 0.12)",
      gate: [-179, 171],
      gateSide: "L"
    },
    {
      id: "pen-south-west",
      name: "🌿 Bosque (SW)",
      species: "Capivara/Cervo",
      minX: -214, maxX: -180, minZ: 184, maxZ: 196,
      color: "#22c55e",
      bg: "rgba(34, 197, 94, 0.12)",
      gate: [-179, 189],
      gateSide: "L"
    },
    {
      id: "pen-north-east",
      name: "🎋 Pandas (NE)",
      species: "Panda/Camelo",
      minX: -170, maxX: -136, minZ: 165, maxZ: 177,
      color: "#eab308",
      bg: "rgba(234, 179, 8, 0.12)",
      gate: [-171, 171],
      gateSide: "R"
    },
    {
      id: "pen-south-east",
      name: "🦒 Savana (SE)",
      species: "Elefante/Girafa",
      minX: -170, maxX: -136, minZ: 184, maxZ: 196,
      color: "#f97316",
      bg: "rgba(249, 115, 22, 0.12)",
      gate: [-171, 189],
      gateSide: "R"
    }
  ];

  enclosures.forEach(enc => {
    const ex = toScreenX(enc.minX);
    const ey = toScreenY(enc.minZ);
    const ew = (enc.maxX - enc.minX) * scale;
    const eh = (enc.maxZ - enc.minZ) * scale;

    // Fundo da jaula
    ctx.fillStyle = enc.bg;
    ctx.fillRect(ex, ey, ew, eh);

    // Cerca de contenção
    ctx.strokeStyle = enc.color;
    ctx.lineWidth = 1.5;
    ctx.strokeRect(ex, ey, ew, eh);

    // Rótulo da jaula
    ctx.fillStyle = enc.color;
    ctx.font = "bold 10px monospace";
    ctx.fillText(enc.name, ex + 4, ey + 12);
    ctx.fillStyle = "rgba(255, 255, 255, 0.55)";
    ctx.font = "9px monospace";
    ctx.fillText(enc.species, ex + 4, ey + 22);

    // 4. Portão Duplo A* (PortalDetector)
    const [gx, gz] = enc.gate;
    const gsx = toScreenX(gx);
    const gsy = toScreenY(gz);

    // Marcador do Portão
    ctx.fillStyle = "#facc15";
    ctx.beginPath();
    ctx.arc(gsx, gsy, 3.5, 0, Math.PI * 2);
    ctx.fill();

    ctx.strokeStyle = "#fef08a";
    ctx.lineWidth = 1;
    ctx.stroke();

    ctx.fillStyle = "#fef08a";
    ctx.font = "bold 9px monospace";
    if (enc.gateSide === "L") {
      ctx.fillText("🚪", gsx - 12, gsy + 3);
    } else {
      ctx.fillText("🚪", gsx + 4, gsy + 3);
    }
  });

  // Legenda de Status no Rodapé
  ctx.fillStyle = "#64748b";
  ctx.font = "9px monospace";
  ctx.fillText("4 Jaulas • 4 Portais A* (TRAVERSE_PORTAL) • Corredor Central", 8, h - 8);

  // 5. Desenhar Agentes
  const agents = data.agents || {};
  Object.keys(agents).forEach(k => {
    const ag = agents[k];
    if (!ag.pos) return;
    const [ax, , az] = ag.pos;
    const sx = toScreenX(ax);
    const sy = toScreenY(az);

    const isKarlach = k.toLowerCase().includes("karlach");
    const color = isKarlach ? "#f97316" : "#38bdf8";

    // Aura pulsante
    ctx.fillStyle = isKarlach ? "rgba(249, 115, 22, 0.2)" : "rgba(56, 189, 248, 0.2)";
    ctx.beginPath();
    ctx.arc(sx, sy, 11, 0, Math.PI * 2);
    ctx.fill();

    // Ponto central
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(sx, sy, 6, 0, Math.PI * 2);
    ctx.fill();

    ctx.strokeStyle = "#fff";
    ctx.lineWidth = 1.5;
    ctx.stroke();

    // Rótulo
    ctx.fillStyle = "#fff";
    ctx.font = "bold 10px monospace";
    ctx.fillText(`${k.toUpperCase()}`, sx + 9, sy - 2);

    ctx.fillStyle = "rgba(255, 255, 255, 0.6)";
    ctx.font = "9px monospace";
    ctx.fillText(`[${ax.toFixed(1)}, ${az.toFixed(1)}]`, sx + 9, sy + 8);
  });
}

// Configura eventos da UI no carregamento
if (typeof window !== "undefined") {
  window.addEventListener("DOMContentLoaded", () => {
    connectStudioSocket();
    fetchStatus();
    setInterval(fetchStatus, 1500);

    // Botões de Ação Rápida
    const btnShelter = document.getElementById("btn-shelter");
    if (btnShelter) {
      btnShelter.addEventListener("click", () => {
        sendChatMessage("karlach, está chovendo, vá se abrigar!");
      });
    }

    const btnRescue = document.getElementById("btn-rescue");
    if (btnRescue) {
      btnRescue.addEventListener("click", () => {
        sendChatMessage("karlach, a capivarinha filhote está fora da grade, conduz ela pra dentro da jaula apropriada");
      });
    }

    const btnPatrol = document.getElementById("btn-patrol");
    if (btnPatrol) {
      btnPatrol.addEventListener("click", () => {
        sendChatMessage("karlach, volta pra patrulha no santuario!");
      });
    }

    const btnDance = document.getElementById("btn-dance");
    if (btnDance) {
      btnDance.addEventListener("click", () => {
        sendChatMessage("karlach, dança o passinho de avernus!");
      });
    }

    const btnWeather = document.getElementById("btn-picard-weather");
    if (btnWeather) {
      btnWeather.addEventListener("click", () => {
        sendChatMessage("picard, como está o tempo?");
      });
    }

    const btnClearFeed = document.getElementById("btn-clear-feed");
    if (btnClearFeed) {
      btnClearFeed.addEventListener("click", () => {
        clearCognitiveFeed();
      });
    }

    // Formulário de Chat Customizado
    const form = document.getElementById("chat-form");
    const input = document.getElementById("chat-input");
    if (form && input) {
      form.addEventListener("submit", (e) => {
        e.preventDefault();
        const msg = input.value.trim();
        if (msg) {
          sendChatMessage(msg);
          input.value = "";
        }
      });
    }
  });
}
