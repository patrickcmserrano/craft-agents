// Craft Agents Studio — Voyager 2.0 Native JVM Frontend (CA-040 / CA-041 / CA-056)

let wsInstance = null;
let lastEventsJson = "";
let lastSkillsJson = "";
let lastAgentsJson = "";
let lastLeasesJson = "";
let lastPlayersJson = "";
let activeFeedFilter = "all";
let activeRadarView = "sanctuary"; // "sanctuary", "karlach", "picard"
let currentSpeaker = localStorage.getItem("craft_agents_speaker") || "pace";
let latestTelemetry = null;

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

export async function sendChatMessage(message, speaker = null) {
  if (!message || !message.trim()) return;
  const chosenSpeaker = speaker || getSelectedSpeaker();
  try {
    const res = await fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ speaker: chosenSpeaker, message: message.trim() })
    });
    if (res.ok) {
      console.log(`[Studio] [${chosenSpeaker}] Comando enviado:`, message);
      setTimeout(fetchStatus, 150);
    }
  } catch (e) {
    console.error("[Studio] Erro ao enviar comando:", e);
  }
}

function getSelectedSpeaker() {
  const select = document.getElementById("speaker-select");
  const custom = document.getElementById("speaker-custom");
  if (select && select.value === "custom" && custom && custom.value.trim()) {
    return custom.value.trim();
  }
  return select ? select.value : currentSpeaker;
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

function escapeHtml(str) {
  if (!str) return "";
  return String(str).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function renderTelemetry(data) {
  latestTelemetry = data;
  const root = document.getElementById("studio-root");
  if (!root) return;
  root.dataset.status = "connected";

  // Server Tick
  const tickEl = document.getElementById("server-tick");
  if (tickEl && data.tick !== undefined) {
    tickEl.textContent = `Tick: ${data.tick}`;
  }

  // Weather Badge
  const weatherBadge = document.getElementById("weather-badge");
  if (weatherBadge && data.weather) {
    const w = data.weather;
    const rainIcon = w.raining? (w.thundering? "⛈️" : "🌧️") : "☀️";
    weatherBadge.textContent = `${rainIcon} ${w.clock || "12:00"} (${w.phase || "Dia"}) • ${w.sky || "Céu Limpo"}`;
  }

  // Players Badge & Panel
  const playersBadge = document.getElementById("players-badge");
  const playersCountBadge = document.getElementById("player-count-badge");
  const playersContainer = document.getElementById("players-list-container");
  const allPlayers = data.players || [];
  const players = allPlayers.filter(p => !["karlach", "picard", "antigravity"].includes(p.name.toLowerCase()));
  if (playersBadge) {
    playersBadge.textContent = `👥 ${players.length} jogador${players.length === 1 ? '' : 'es'}`;
  }
  if (playersCountBadge) {
    playersCountBadge.textContent = `${players.length} online`;
  }
  const playersJson = JSON.stringify(players);
  if (playersJson !== lastPlayersJson) {
    lastPlayersJson = playersJson;
    if (playersContainer) {
      if (players.length === 0) {
        playersContainer.innerHTML = `<p style="color:#94a3b8">Nenhum jogador humano conectado no momento.</p>`;
      } else {
        playersContainer.innerHTML = players.map(p => `
          <div class="player-chip">
            <span>🟢 <strong>${p.name}</strong></span>
            <span class="coord">[${p.x.toFixed(1)}, ${p.y.toFixed(0)}, ${p.z.toFixed(1)}]</span>
          </div>
        `).join("");
      }
    }
  }

  // 1. Agentes
  const agentsJson = JSON.stringify({ agents: data.agents || {}, inv: data.inventory || {} });
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
          const isKarlach = k.toLowerCase().includes("karlach");
          const posStr = ag.pos ? `[${ag.pos.map(n => Number(n).toFixed(1)).join(", ")}]` : "N/A";
          const invObj = isKarlach ? (data.inventory?.karlach?.items || {}) : {};
          const invKeys = Object.keys(invObj);
          const invChipsHtml = invKeys.length ? `
            <div class="agent-inv-chips">
              ${invKeys.slice(0, 8).map(ik => `<span class="inv-chip">${invObj[ik]}x ${ik.replace("minecraft:", "")}</span>`).join("")}
              ${invKeys.length > 8 ? `<span class="inv-chip">+${invKeys.length - 8}</span>` : ''}
            </div>
          ` : '';

          return `
            <div class="agent-card">
              <div class="agent-card-header">
                <span class="agent-name">${isKarlach ? "🔥 " : "🖖 "}${k.toUpperCase()}</span>
                <span class="agent-pos">${posStr}</span>
              </div>
              <div class="agent-task"><strong>Status:</strong> ${ag.status || "ativo"}</div>
              ${ag.task ? `<div class="agent-task"><strong>Tarefa:</strong> ${ag.task}</div>` : ''}
              ${ag.last_learned ? `<div class="agent-task"><strong>Última Skill:</strong> <span style="color:#fbbf24">${ag.last_learned}</span></div>` : ''}
              ${invChipsHtml}
              ${ag.skills && ag.skills.length ? `
                <div class="agent-skills-tag">🧠 Skills: ${ag.skills.join(", ")}</div>
              ` : ''}
            </div>
          `;
        }).join("");
      }
    }
  }

  // 2. Actuator Leases
  const leasesJson = JSON.stringify({ leases: data.leases || {}, resources: data.resources || {} });
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

  // 3. Voyager Skill Library
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

  // 4. Feed Cognitivo em Tempo Real
  renderCognitiveFeed(data.events || []);

  // 5. Radar 3D Canvas
  renderRadar(data);
}

function renderCognitiveFeed(events) {
  const eventsJson = JSON.stringify({ events: events, filter: activeFeedFilter });
  if (eventsJson === lastEventsJson) return;
  lastEventsJson = eventsJson;

  const logsEl = document.getElementById("cognitive-logs");
  if (!logsEl) return;

  let filtered = events;
  if (activeFeedFilter === "stimulus") {
    filtered = events.filter(e => (e.type || "").toLowerCase().includes("stimulus"));
  } else if (activeFeedFilter === "router") {
    filtered = events.filter(e => (e.type || "").toLowerCase().includes("router"));
  } else if (activeFeedFilter === "motor") {
    filtered = events.filter(e => (e.type || "").toLowerCase().includes("motor"));
  } else if (activeFeedFilter === "critic") {
    filtered = events.filter(e => (e.type || "").toLowerCase().includes("critic") || (e.type || "").toLowerCase().includes("gatekeeper"));
  }

  if (filtered.length === 0) {
    logsEl.innerHTML = `<p style="color:#94a3b8">Nenhum evento registrado para o filtro '${activeFeedFilter}'.</p>`;
    return;
  }

  logsEl.innerHTML = filtered.map(evt => {
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

  logsEl.scrollTop = logsEl.scrollHeight;
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

  // Determina centro e escala de acordo com activeRadarView
  let centerWorldX = -175.0;
  let centerWorldZ = 178.0;
  let scale = 3.6;

  const karlachPos = data.agents?.karlach?.pos;
  const picardPos = data.agents?.picard?.pos;

  if (activeRadarView === "karlach" && karlachPos) {
    centerWorldX = karlachPos[0];
    centerWorldZ = karlachPos[2];
    scale = 4.5;
  } else if (activeRadarView === "picard" && picardPos) {
    centerWorldX = picardPos[0];
    centerWorldZ = picardPos[2];
    scale = 4.2;
  }

  const toScreenX = (wx) => (w / 2) + (wx - centerWorldX) * scale;
  const toScreenY = (wz) => (h / 2) + (wz - centerWorldZ) * scale;

  // Grid
  ctx.strokeStyle = "#131f33";
  ctx.lineWidth = 1;
  const step = 28;
  for (let x = 0; x < w; x += step) {
    ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke();
  }
  for (let y = 0; y < h; y += step) {
    ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
  }

  // Se visualizando Santuário ou Karlach nas proximidades do Santuário
  const distToSanctuary = Math.hypot(centerWorldX - (-175), centerWorldZ - 178);
  if (distToSanctuary < 80) {
    // 1. Lago Norte
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
    ctx.fillText("🌊 Lago Norte (Capivaras / Área Aquática)", lakeX1 + 6, lakeY1 + 12);

    // 2. Corredor Central
    const corrX = toScreenX(-179);
    const corrY = toScreenY(165);
    const corrW = ((-171) - (-179)) * scale;
    const corrH = (196 - 165) * scale;
    ctx.fillStyle = "rgba(51, 65, 85, 0.25)";
    ctx.strokeStyle = "#334155";
    ctx.lineWidth = 1;
    ctx.fillRect(corrX, corrY, corrW, corrH);
    ctx.strokeRect(corrX, corrY, corrW, corrH);

    // 3. As 4 Jaulas Individuais do Santuário (com Gatos incluídos)
    const enclosures = [
      {
        id: "pen-north-west",
        name: "❄️ Tundra (NW)",
        species: "Mamutes & Raposas",
        minX: -214, maxX: -180, minZ: 165, maxZ: 177,
        color: "#0ea5e9",
        bg: "rgba(14, 165, 233, 0.12)",
        gate: [-179, 171],
        gateSide: "L"
      },
      {
        id: "pen-south-west",
        name: "🌿 Bosque (SW)",
        species: "Capivaras, Gatos & Cervos",
        minX: -214, maxX: -180, minZ: 184, maxZ: 196,
        color: "#22c55e",
        bg: "rgba(34, 197, 94, 0.12)",
        gate: [-179, 189],
        gateSide: "L"
      },
      {
        id: "pen-north-east",
        name: "🎋 Pandas & Gatos (NE)",
        species: "Pandas, Gatos & Camelos",
        minX: -170, maxX: -136, minZ: 165, maxZ: 177,
        color: "#eab308",
        bg: "rgba(234, 179, 8, 0.12)",
        gate: [-171, 171],
        gateSide: "R"
      },
      {
        id: "pen-south-east",
        name: "🦒 Savana (SE)",
        species: "Elefantes, Girafas & Zebras",
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

      ctx.fillStyle = enc.bg;
      ctx.fillRect(ex, ey, ew, eh);

      ctx.strokeStyle = enc.color;
      ctx.lineWidth = 1.5;
      ctx.strokeRect(ex, ey, ew, eh);

      ctx.fillStyle = enc.color;
      ctx.font = "bold 10px monospace";
      ctx.fillText(enc.name, ex + 4, ey + 12);
      ctx.fillStyle = "rgba(255, 255, 255, 0.65)";
      ctx.font = "9px monospace";
      ctx.fillText(enc.species, ex + 4, ey + 22);

      // Portão Duplo A*
      const [gx, gz] = enc.gate;
      const gsx = toScreenX(gx);
      const gsy = toScreenY(gz);

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
  } else if (activeRadarView === "picard") {
    // Esboço da Ponte da Enterprise-D em torno de [-65, 101]
    const bridgeX = toScreenX(-75);
    const bridgeY = toScreenY(91);
    const bridgeW = 20 * scale;
    const bridgeH = 20 * scale;

    ctx.fillStyle = "rgba(168, 85, 247, 0.1)";
    ctx.strokeStyle = "#c084fc";
    ctx.lineWidth = 1.5;
    ctx.strokeRect(bridgeX, bridgeY, bridgeW, bridgeH);
    ctx.fillRect(bridgeX, bridgeY, bridgeW, bridgeH);

    ctx.fillStyle = "#c084fc";
    ctx.font = "bold 11px monospace";
    ctx.fillText("🛸 Ponte da USS Enterprise-D (NCC-1701-D)", bridgeX + 6, bridgeY + 14);
    ctx.fillStyle = "rgba(255, 255, 255, 0.6)";
    ctx.font = "9px monospace";
    ctx.fillText("Estação de Comando • Leme • Meteorologia Orbital", bridgeX + 6, bridgeY + 26);
  }

  // 4. Desenhar Jogadores Humanos Conectados
  const allPlayers = data.players || [];
  const players = allPlayers.filter(p => !["karlach", "picard", "antigravity"].includes(p.name.toLowerCase()));
  players.forEach(p => {
    const px = toScreenX(p.x);
    const py = toScreenY(p.z);

    // Aura verde
    ctx.fillStyle = "rgba(34, 197, 94, 0.25)";
    ctx.beginPath();
    ctx.arc(px, py, 10, 0, Math.PI * 2);
    ctx.fill();

    // Ponto verde
    ctx.fillStyle = "#22c55e";
    ctx.beginPath();
    ctx.arc(px, py, 5, 0, Math.PI * 2);
    ctx.fill();

    ctx.strokeStyle = "#fff";
    ctx.lineWidth = 1.5;
    ctx.stroke();

    ctx.fillStyle = "#86efac";
    ctx.font = "bold 10px monospace";
    ctx.fillText(p.name, px + 8, py - 2);
    ctx.fillStyle = "rgba(255, 255, 255, 0.6)";
    ctx.font = "9px monospace";
    ctx.fillText(`[${p.x.toFixed(1)}, ${p.z.toFixed(1)}]`, px + 8, py + 8);
  });

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

    ctx.fillStyle = isKarlach ? "rgba(249, 115, 22, 0.25)" : "rgba(56, 189, 248, 0.25)";
    ctx.beginPath();
    ctx.arc(sx, sy, 12, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(sx, sy, 6, 0, Math.PI * 2);
    ctx.fill();

    ctx.strokeStyle = "#fff";
    ctx.lineWidth = 1.5;
    ctx.stroke();

    ctx.fillStyle = "#fff";
    ctx.font = "bold 10px monospace";
    ctx.fillText(`${k.toUpperCase()}`, sx + 9, sy - 2);

    ctx.fillStyle = "rgba(255, 255, 255, 0.7)";
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

    // Speaker Selector
    const speakerSelect = document.getElementById("speaker-select");
    const speakerCustom = document.getElementById("speaker-custom");
    if (speakerSelect) {
      if (currentSpeaker && ["pace", "Lizzie"].includes(currentSpeaker)) {
        speakerSelect.value = currentSpeaker;
      } else if (currentSpeaker) {
        speakerSelect.value = "custom";
        if (speakerCustom) {
          speakerCustom.style.display = "inline-block";
          speakerCustom.value = currentSpeaker;
        }
      }
      speakerSelect.addEventListener("change", () => {
        if (speakerSelect.value === "custom") {
          if (speakerCustom) {
            speakerCustom.style.display = "inline-block";
            speakerCustom.focus();
          }
        } else {
          if (speakerCustom) speakerCustom.style.display = "none";
          currentSpeaker = speakerSelect.value;
          localStorage.setItem("craft_agents_speaker", currentSpeaker);
        }
      });
    }
    if (speakerCustom) {
      speakerCustom.addEventListener("input", () => {
        currentSpeaker = speakerCustom.value.trim() || "pace";
        localStorage.setItem("craft_agents_speaker", currentSpeaker);
      });
    }

    // Action Category Tabs
    const tabBtns = document.querySelectorAll(".tab-btn");
    tabBtns.forEach(btn => {
      btn.addEventListener("click", () => {
        tabBtns.forEach(b => b.classList.remove("active"));
        btn.classList.add("active");
        const cat = btn.dataset.category;
        document.querySelectorAll(".quick-buttons-group").forEach(g => g.classList.remove("active"));
        const targetGroup = document.getElementById(`group-${cat}`);
        if (targetGroup) targetGroup.classList.add("active");
      });
    });

    // Quick Action Buttons com data-msg
    document.querySelectorAll(".quick-buttons-group button").forEach(btn => {
      btn.addEventListener("click", () => {
        const msg = btn.dataset.msg;
        if (msg) {
          sendChatMessage(msg);
        }
      });
    });

    // Radar View Mode Buttons
    const viewSanctuary = document.getElementById("radar-view-sanctuary");
    const viewKarlach = document.getElementById("radar-view-karlach");
    const viewPicard = document.getElementById("radar-view-picard");
    const radarViewBtns = [viewSanctuary, viewKarlach, viewPicard].filter(Boolean);

    radarViewBtns.forEach(b => {
      b.addEventListener("click", () => {
        radarViewBtns.forEach(x => x.classList.remove("active"));
        b.classList.add("active");
        if (b === viewSanctuary) activeRadarView = "sanctuary";
        if (b === viewKarlach) activeRadarView = "karlach";
        if (b === viewPicard) activeRadarView = "picard";
        if (latestTelemetry) renderRadar(latestTelemetry);
      });
    });

    // Cognitive Feed Filter Buttons
    const filterBtns = document.querySelectorAll(".feed-filter-btn");
    filterBtns.forEach(btn => {
      btn.addEventListener("click", () => {
        filterBtns.forEach(b => b.classList.remove("active"));
        btn.classList.add("active");
        activeFeedFilter = btn.dataset.filter || "all";
        lastEventsJson = ""; // Força re-render
        if (latestTelemetry) renderCognitiveFeed(latestTelemetry.events || []);
      });
    });

    // Botão Limpar Feed
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
