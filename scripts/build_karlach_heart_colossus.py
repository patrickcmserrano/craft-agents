#!/usr/bin/env python3
"""
Construtor Topográfico Procedural: Karlach's Heart Colossus (O Motor Infernal Titânico)
Ergue a escultura mecânica monumental do coração infernal de Karlach,
conectada por uma ponte de correntes à ponta da Klegger Corp sobre o lago de lava.
"""

import socket
import time
import math

BRIDGE_HOST = "127.0.0.1"
BRIDGE_PORT = 25595

def connect_bridge():
    s = socket.socket()
    s.connect((BRIDGE_HOST, BRIDGE_PORT))
    s.settimeout(5.0)
    return s

def send_cmd(s, cmd_str):
    s.sendall(f"CMD|{cmd_str}\n".encode("utf-8"))
    try:
        return s.recv(1024).decode("utf-8").strip()
    except:
        return ""

def set_block(s, x, y, z, block_id, mode="replace"):
    return send_cmd(s, f"setblock {x} {y} {z} {block_id} {mode}")

def get_block(s, x, y, z):
    s.sendall(f"BLOCK|{x}|{y}|{z}\n".encode("utf-8"))
    resp = s.recv(1024).decode("utf-8").strip()
    if "|" in resp:
        return resp.split("|")[1]
    return "minecraft:air"

def play_sound(s, x, y, z, sound="minecraft:block.anvil.use", vol=1.0, pitch=1.0):
    send_cmd(s, f"playsound {sound} master @a {x} {y} {z} {vol:.1f} {pitch:.1f}")

def particle(s, x, y, z, p="minecraft:flame", count=10):
    send_cmd(s, f"particle {p} {x:.1f} {y:.1f} {z:.1f} 0.4 0.4 0.4 0.05 {count} normal")

def karlach_say(s, msg):
    escaped = msg.replace('"', '\\"')
    send_cmd(s, f'tellraw @a [{{"text":"<Karlach> ","color":"red","bold":true}},{{"text":"{escaped}","color":"gold"}}]')

def build_karlach_heart_colossus():
    print("[+] Conectando à In-Server Bridge...")
    s = connect_bridge()

    karlach_say(s, "PELA PRIMEIRA VEZ FORA DE AVERNUS... O meu Motor Infernal em escala colossal! Vejam as engrenagens rugirem sobre a lava! 🔥 ⚙️")
    play_sound(s, 168, -51, -106, "minecraft:item.armor.equip_netherite", 1.2, 0.7)
    time.sleep(1.0)

    # -------------------------------------------------------------------------
    # 1. PONTE SUSPENSA DE CORRENTES & VIGAS (Conexão Klegger Corp -> Colosso)
    # De X=169 até X=177 em Z=-106..-105, Y=-51
    # -------------------------------------------------------------------------
    print("[1/6] Estendendo Ponte Suspensa de Correntes entre Klegger Corp e o Colosso...")
    for bx in range(169, 178):
        for bz in [-106, -105]:
            set_block(s, bx, -51, bz, "minecraft:weathered_cut_copper_slab[type=top]")
        set_block(s, bx, -50, -107, "minecraft:chain[axis=x]")
        set_block(s, bx, -50, -104, "minecraft:chain[axis=x]")
        if bx % 3 == 0:
            set_block(s, bx, -50, -107, "minecraft:iron_bars")
            set_block(s, bx, -50, -104, "minecraft:iron_bars")
            set_block(s, bx, -49, -107, "minecraft:soul_lantern[hanging=false]")

    play_sound(s, 173, -51, -106, "minecraft:block.chain.place", 1.0, 1.2)
    particle(s, 173, -50, -106, "minecraft:soul_fire_flame", 15)

    # -------------------------------------------------------------------------
    # 2. PILARES MASSIVOS DE SUSTENTAÇÃO SUBMERSOS NA LAVA
    # Centrados em torno do Colosso: X=178, 186; Z=-110, -102
    # -------------------------------------------------------------------------
    print("[2/6] Afundando Pilares Tectônicos de Netherite e Blackstone na Lava...")
    pilar_bases = [(178, -110), (186, -110), (178, -102), (186, -102)]
    for px, pz in pilar_bases:
        for py in range(-52, -64, -1):
            b = get_block(s, px, py, pz)
            mat = "minecraft:gilded_blackstone" if py % 3 == 0 else "minecraft:chiseled_polished_blackstone"
            set_block(s, px, py, pz, mat)
            if b != "minecraft:air" and "lava" not in b and py < -56:
                break

    # -------------------------------------------------------------------------
    # 3. PASSARELA OCTOGONAL DE OBSERVAÇÃO (360° em torno do Motor)
    # Centro: (182, -51, -106), Raio ~ 5 blocos
    # -------------------------------------------------------------------------
    print("[3/6] Construindo Mezanino Octogonal de Observação 360°...")
    cx, cy, cz = 182, -51, -106
    for x in range(176, 189):
        for z in range(-113, -99):
            dx = abs(x - cx)
            dz = abs(z - cz)
            d_octo = dx + dz
            # Anel estreito entre raio 4 e raio 6
            if 4 <= max(dx, dz) <= 6 and d_octo <= 8:
                set_block(s, x, cy, z, "minecraft:polished_blackstone_bricks")
                # Corrimão externo
                if max(dx, dz) == 6 or d_octo == 8:
                    if not (x in range(176, 178) and z in [-106, -105]): # abertura da ponte
                        set_block(s, x, cy + 1, z, "minecraft:iron_bars")
                # Lanternas nas quinas
                if dx == dz and dx == 4:
                    set_block(s, x, cy + 1, z, "minecraft:copper_grate")
                    set_block(s, x, cy + 2, z, "minecraft:soul_lantern[hanging=false]")

    play_sound(s, cx, cy, cz, "minecraft:block.beacon.activate", 1.0, 1.2)
    time.sleep(0.5)

    # -------------------------------------------------------------------------
    # 4. O NÚCLEO INFERNAL: ESFERA DO CORAÇÃO PULSANTE
    # Centro do Coração: (182, -47, -106), Raio 3
    # -------------------------------------------------------------------------
    print("[4/6] Forjando a Esfera do Coração Mecânico (O Núcleo Infernal)...")
    hx, hy, hz = 182, -47, -106
    for x in range(hx - 3, hx + 4):
        for y in range(hy - 3, hy + 4):
            for z in range(hz - 3, hz + 4):
                dx = x - hx
                dy = y - hy
                dz = z - hz
                r_sq = dx*dx + dy*dy + dz*dz

                # Miolo pulsante mais interno (r <= 1.5)
                if r_sq <= 2:
                    set_block(s, x, y, z, "minecraft:shroomlight")
                # Camada de magma incandescente (1.5 < r <= 2.2)
                elif r_sq <= 5:
                    set_block(s, x, y, z, "minecraft:magma_block")
                # Blindagem externa esférica com aberturas vazadas (2.2 < r <= 3.2)
                elif r_sq <= 10:
                    # Grelhas e frestas de ferro para ver o fogo interior
                    if abs(dx) == abs(dz) or abs(dx) == abs(dy) or abs(dy) == abs(dz):
                        set_block(s, x, y, z, "minecraft:copper_grate")
                    elif r_sq >= 8:
                        set_block(s, x, y, z, "minecraft:gilded_blackstone")
                    else:
                        set_block(s, x, y, z, "minecraft:iron_bars")

    # Altar superior de combustão: Soul Campfires liberando fumaça infernal contínua
    set_block(s, hx, hy + 4, hz, "minecraft:soul_campfire")
    set_block(s, hx - 1, hy + 3, hz, "minecraft:chiseled_polished_blackstone")
    set_block(s, hx + 1, hy + 3, hz, "minecraft:chiseled_polished_blackstone")
    set_block(s, hx, hy + 3, hz - 1, "minecraft:chiseled_polished_blackstone")
    set_block(s, hx, hy + 3, hz + 1, "minecraft:chiseled_polished_blackstone")

    play_sound(s, hx, hy, hz, "minecraft:block.anvil.place", 1.2, 0.6)
    particle(s, hx, hy + 4, hz, "minecraft:soul_fire_flame", 40)
    time.sleep(0.5)

    # -------------------------------------------------------------------------
    # 5. AS GRANDES ENGRENAGENS INDUSTRIAIS (Clockwork Gear Rings)
    # -------------------------------------------------------------------------
    print("[5/6] Instalando Engrenagens Dentadas de Cobre e Bielas de Alta Pressão...")
    # Anel vertical frontal (plano Y-Z em X=hx)
    for angle_deg in range(0, 360, 15):
        rad = math.radians(angle_deg)
        gz = int(round(hz + 4.5 * math.cos(rad)))
        gy = int(round(hy + 4.5 * math.sin(rad)))
        # Bloco da engrenagem
        mat = "minecraft:weathered_cut_copper" if (angle_deg % 30 == 0) else "minecraft:cut_copper"
        set_block(s, hx, gy, gz, mat)
        # Dente da engrenagem projetado para fora a cada 30 graus
        if angle_deg % 30 == 0:
            dz_out = int(round(math.cos(rad)))
            dy_out = int(round(math.sin(rad)))
            set_block(s, hx, gy + dy_out, gz + dz_out, "minecraft:copper_block")

    # Anel horizontal de transmissão (plano X-Z em Y=hy)
    for angle_deg in range(0, 360, 15):
        rad = math.radians(angle_deg)
        gx = int(round(hx + 4.5 * math.cos(rad)))
        gz = int(round(hz + 4.5 * math.sin(rad)))
        mat = "minecraft:exposed_cut_copper" if (angle_deg % 30 == 0) else "minecraft:cut_copper"
        set_block(s, gx, hy, gz, mat)

    # -------------------------------------------------------------------------
    # 6. PISTÕES LATERAIS & CHAMINÉS DE ALÍVIO DE VAPOR
    # -------------------------------------------------------------------------
    print("[6/6] Ajustando Pistões de Basalto e Chaminés de Escape...")
    # Pistão Norte (Z = hz - 4 .. hz - 7)
    for pz in range(hz - 7, hz - 3):
        set_block(s, hx, hy, pz, "minecraft:polished_basalt[axis=z]")
    set_block(s, hx, hy, hz - 7, "minecraft:heavy_core")
    set_block(s, hx, hy + 1, hz - 7, "minecraft:iron_bars")
    set_block(s, hx, hy - 1, hz - 7, "minecraft:chain[axis=y]")

    # Pistão Sul (Z = hz + 4 .. hz + 7)
    for pz in range(hz + 4, hz + 8):
        set_block(s, hx, hy, pz, "minecraft:polished_basalt[axis=z]")
    set_block(s, hx, hy, hz + 7, "minecraft:heavy_core")
    set_block(s, hx, hy + 1, hz + 7, "minecraft:iron_bars")
    set_block(s, hx, hy - 1, hz + 7, "minecraft:chain[axis=y]")

    # Tubulações verticais de sucção mergulhando na lava
    for sy in range(hy - 4, -56, -1):
        set_block(s, hx - 2, sy, hz, "minecraft:copper_grate")
        set_block(s, hx + 2, sy, hz, "minecraft:copper_grate")
        set_block(s, hx, sy, hz - 2, "minecraft:polished_blackstone")
        set_block(s, hx, sy, hz + 2, "minecraft:polished_blackstone")

    # Toque final com sinfonia de ativação
    play_sound(s, hx, hy, hz, "minecraft:ui.toast.challenge_complete", 1.0, 0.9)
    play_sound(s, hx, hy, hz, "minecraft:block.end_portal.spawn", 0.8, 1.3)
    karlach_say(s, "O MOTOR INFERNAL TÁ VIVO! Engrenagens em rotação máxima, coração de magma a todo vapor e chamas azuis rasgando o teto da caverna! Podem cruzar a ponte de correntes e sentir o calor! 🔥 ⚙️ ⚡")

    s.close()
    print("[+] Karlach's Heart Colossus erguido com sucesso total!")

if __name__ == "__main__":
    build_karlach_heart_colossus()
