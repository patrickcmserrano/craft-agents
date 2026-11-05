#!/usr/bin/env python3
"""
Construtor Topográfico Procedural: Klegger Corp Mining Facility (Mustafar Replica)
Projeta e ergue a réplica fiel da instalação de mineração de lava da Techno Union
exatamente na frente do jogador, respeitando o lago de lava e o relevo da caverna.
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

def play_sound(s, x, y, z, sound="minecraft:block.deepslate.place", vol=1.0, pitch=1.0):
    send_cmd(s, f"playsound {sound} master @a {x} {y} {z} {vol:.1f} {pitch:.1f}")

def particle(s, x, y, z, p="minecraft:lava", count=5):
    send_cmd(s, f"particle {p} {x:.1f} {y:.1f} {z:.1f} 0.3 0.3 0.3 0.05 {count} normal")

def karlach_say(s, msg):
    escaped = msg.replace('"', '\\"')
    send_cmd(s, f'tellraw @a [{{"text":"<Karlach> ","color":"red","bold":true}},{{"text":"{escaped}","color":"gold"}}]')

def build_klegger_corp_facility():
    print("[+] Conectando ao In-Server Bridge...")
    s = connect_bridge()

    karlach_say(s, "Soldados! Reconheço essa arquitetura de longe... É a lendária Instalação Mineira de Mustafar da Klegger Corp! Iniciando construção da réplica sobre a lava! 🔥 🌋")
    play_sound(s, 140, -54, -105, "minecraft:item.armor.equip_netherite", 1.2, 0.8)
    time.sleep(1.0)

    # -------------------------------------------------------------------------
    # 1. PASSADIÇO DE ACESSO DA MARGEM (Catwalk)
    # Conecta a margem em X=138 até a plataforma em X=143
    # -------------------------------------------------------------------------
    print("[1/5] Erguendo Passadiço de Acesso da Margem...")
    for x in range(138, 144):
        for z in [-106, -105]:
            # Piso principal
            set_block(s, x, -54, z, "minecraft:polished_blackstone_bricks")
            # Pilares de sustentação descendo até a rocha se estiver sobre lava/ar
            for py in range(-55, -62, -1):
                b = get_block(s, x, py, z)
                if b == "minecraft:air" or "lava" in b:
                    set_block(s, x, py, z, "minecraft:polished_blackstone")
                else:
                    break
        # Guardrails e iluminação
        set_block(s, x, -53, -107, "minecraft:iron_bars")
        set_block(s, x, -53, -104, "minecraft:iron_bars")
    
    # Postes de iluminação de entrada
    set_block(s, 138, -53, -107, "minecraft:polished_deepslate_wall")
    set_block(s, 138, -52, -107, "minecraft:soul_lantern[hanging=false]")
    set_block(s, 138, -53, -104, "minecraft:polished_deepslate_wall")
    set_block(s, 138, -52, -104, "minecraft:soul_lantern[hanging=false]")
    
    play_sound(s, 140, -54, -105, "minecraft:block.anvil.use", 0.8, 1.2)
    particle(s, 140, -53, -105, "minecraft:flame", 12)

    # -------------------------------------------------------------------------
    # 2. PILARES MASSIVOS DE SUSTENTAÇÃO (Descendo até a rocha do fundo da lava)
    # -------------------------------------------------------------------------
    print("[2/5] Cimentando Pilares de Sustentação na Rocha Subterrânea...")
    pillar_coords = [
        (145, -109), (151, -109),
        (145, -103), (151, -103)
    ]
    for px, pz in pillar_coords:
        # Pilar 2x2
        for dx in [0, 1]:
            for dz in [0, 1]:
                col_x = px + dx
                col_z = pz + dz
                for py in range(-55, -64, -1):
                    b = get_block(s, col_x, py, col_z)
                    if b == "minecraft:air" or "lava" in b:
                        mat = "minecraft:chiseled_polished_blackstone" if py % 2 == 0 else "minecraft:polished_blackstone_bricks"
                        set_block(s, col_x, py, col_z, mat)
                    else:
                        set_block(s, col_x, py, col_z, "minecraft:polished_blackstone_bricks")
                        break

    # -------------------------------------------------------------------------
    # 3. PLATAFORMA DE POUSO OCTOGONAL SUSPENSA (Landing Pad de Mustafar)
    # Centro: (148, -54, -106), Raio ~ 5 blocos (11x11 octogonal chanfrada)
    # -------------------------------------------------------------------------
    print("[3/5] Construindo Plataforma de Pouso Octogonal...")
    cx, cy, cz = 148, -54, -106

    for x in range(143, 154):
        for z in range(-111, -100):
            dx = abs(x - cx)
            dz = abs(z - cz)
            # Chanfro octogonal: cantos cortados
            if dx + dz > 7:
                continue

            # Borda exterior
            is_edge = (dx + dz == 7) or (dx == 5) or (dz == 5)

            if is_edge:
                # Quinas com luz de balizamento âmbar
                if dx == dz and dx >= 3:
                    set_block(s, x, cy, z, "minecraft:ochre_froglight")
                    set_block(s, x, cy + 1, z, "minecraft:iron_bars")
                else:
                    set_block(s, x, cy, z, "minecraft:polished_blackstone")
                    # Parapeito em ferro (exceto entrada oeste e saída leste do braço)
                    if not (z in [-106, -105] and x in [143, 153]):
                        set_block(s, x, cy + 1, z, "minecraft:iron_bars")
            else:
                # Desenho geométrico do piso do heliponto
                dist_center = max(dx, dz)
                if dist_center == 0:
                    set_block(s, x, cy, z, "minecraft:chiseled_polished_blackstone")
                elif dist_center == 1:
                    set_block(s, x, cy, z, "minecraft:smooth_quartz")
                elif dist_center == 2:
                    set_block(s, x, cy, z, "minecraft:deepslate_tiles")
                elif dist_center == 3:
                    set_block(s, x, cy, z, "minecraft:cut_copper")
                else:
                    set_block(s, x, cy, z, "minecraft:polished_blackstone_bricks")

    play_sound(s, cx, cy, cz, "minecraft:block.beacon.activate", 1.0, 1.4)
    particle(s, cx, cy + 1, cz, "minecraft:totem_of_undying", 25)
    time.sleep(0.5)

    # -------------------------------------------------------------------------
    # 4. O BRAÇO COLETOR DE LAVA (The Mining Collector Arm - Mustafar Duel)
    # Estende-se em balanço de X=154 até X=168, Z=-106..-105
    # -------------------------------------------------------------------------
    print("[4/5] Estendendo o Braço Coletor de Lava em Balanço sobre o Magma...")
    for bx in range(154, 169):
        # Viga estrutural inferior (sustentação em tesoura)
        viga_y = -54 if bx < 160 else -53
        for bz in [-106, -105]:
            set_block(s, bx, viga_y, bz, "minecraft:polished_blackstone_bricks")
            # Passarela superior
            set_block(s, bx, -52, bz, "minecraft:cut_copper")

        # Corrimão de proteção na passarela do duelo
        set_block(s, bx, -51, -107, "minecraft:iron_bars")
        set_block(s, bx, -51, -104, "minecraft:iron_bars")

        # Anéis Magnéticos de Indução / Refrigeração (Techno Union Collars)
        if bx in [157, 161, 165]:
            for ring_z in [-107, -106, -105, -104]:
                set_block(s, bx, -53, ring_z, "minecraft:weathered_cut_copper")
                set_block(s, bx, -50, ring_z, "minecraft:weathered_cut_copper")
            set_block(s, bx, -52, -108, "minecraft:weathered_cut_copper")
            set_block(s, bx, -51, -108, "minecraft:copper_grate")
            set_block(s, bx, -52, -103, "minecraft:weathered_cut_copper")
            set_block(s, bx, -51, -103, "minecraft:copper_grate")
            # Correntes de tensão
            set_block(s, bx, -49, -108, "minecraft:chain")
            set_block(s, bx, -49, -103, "minecraft:chain")

    # Ponta do Braço Coletor (Extrator e Calha de Magma em X=168)
    head_x = 168
    for hy in range(-55, -48):
        set_block(s, head_x, hy, -106, "minecraft:polished_blackstone")
        set_block(s, head_x, hy, -105, "minecraft:chiseled_deepslate")
    # Núcleo de extração
    set_block(s, head_x, -52, -106, "minecraft:magma_block")
    set_block(s, head_x, -52, -105, "minecraft:shroomlight")
    set_block(s, head_x, -51, -106, "minecraft:soul_campfire")
    set_block(s, head_x, -51, -105, "minecraft:iron_bars")

    play_sound(s, head_x, -52, -106, "minecraft:block.anvil.place", 1.0, 0.8)
    particle(s, head_x, -50, -106, "minecraft:smoke", 30)

    # -------------------------------------------------------------------------
    # 5. SALA DE CONTROLE DA KLEGGER CORP (Observation Center na encosta)
    # Lado norte da plataforma: X=146..150, Z=-112..-115, Y=-54..-50
    # -------------------------------------------------------------------------
    print("[5/5] Erguendo Centro de Comando da Klegger Corp...")
    for rx in range(146, 151):
        for rz in range(-116, -111):
            for ry in range(-54, -49):
                wall_edge = (rx in [146, 150]) or (rz in [-116, -112])
                if ry == -54:
                    # Piso da sala
                    set_block(s, rx, ry, rz, "minecraft:polished_blackstone")
                elif ry == -49:
                    # Teto inclinado
                    set_block(s, rx, ry, rz, "minecraft:polished_blackstone_bricks")
                elif wall_edge:
                    # Janelas panorâmicas viradas para a lava
                    if rz == -112 and rx in [147, 148, 149]:
                        set_block(s, rx, ry, rz, "minecraft:tinted_glass")
                    elif rx == 148 and rz == -112 and ry == -53:
                        # Porta de entrada da sala
                        set_block(s, rx, ry, rz, "minecraft:air")
                    else:
                        set_block(s, rx, ry, rz, "minecraft:polished_blackstone_bricks")
                else:
                    # Interior livre
                    set_block(s, rx, ry, rz, "minecraft:air")

    # Detalhes da Sala de Controle (Mesa Holográfica, Painéis e Antenas)
    set_block(s, 148, -53, -114, "minecraft:chiseled_polished_blackstone")
    set_block(s, 148, -52, -114, "minecraft:sea_lantern") # Projetor holográfico
    set_block(s, 147, -53, -115, "minecraft:crafting_table")
    set_block(s, 149, -53, -115, "minecraft:blast_furnace")
    set_block(s, 148, -53, -115, "minecraft:trapped_chest")
    set_block(s, 148, -50, -114, "minecraft:soul_lantern[hanging=true]")
    # Antena externa de transmissão separatista
    set_block(s, 148, -48, -114, "minecraft:lightning_rod")

    # Finalização triunfal
    play_sound(s, cx, cy, cz, "minecraft:ui.toast.challenge_complete", 1.0, 1.0)
    karlach_say(s, "OBRA CONCLUÍDA, SOLDADOS! A Klegger Corp Mining Facility de Mustafar tá de pé no meio do lago de lava! Plataforma de pouso, braço coletor e centro de comando integrados perfeitamente ao relevo! 🔥 ⚔️")

    s.close()
    print("[+] Klegger Corp Mining Facility erguida com sucesso!")

if __name__ == "__main__":
    build_klegger_corp_facility()
