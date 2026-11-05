#!/usr/bin/env python3
"""
Construtor Topográfico: Ponte Suspensa do Abismo de Lava (Extensão Leste)
Estende a passarela a partir do Karlach's Heart Colossus (X=188)
através de 56 blocos sobre o lago de lava até a margem rochosa leste (X=244..248),
com pilares monumentais de sustentação e posto de observação leste.
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

def build_east_bridge():
    print("[+] Conectando à In-Server Bridge...")
    s = connect_bridge()

    karlach_say(s, "Estendendo os cabos do abismo! A ponte vai cruzar o mar de fogo até a margem leste! 🔗🔥")
    play_sound(s, 188, -50, -106, "minecraft:block.chain.place", 1.2, 0.8)
    time.sleep(1.0)

    # -------------------------------------------------------------------------
    # 1. ABERTURA DO PORTAL LESTE NO MEZANINO DO COLOSSO (X=188)
    # -------------------------------------------------------------------------
    print("[1/5] Abrindo pórtico no mirante do Colosso...")
    # Remove as grades de ferro do corrimão para liberar a passagem
    set_block(s, 188, -50, -106, "minecraft:air")
    set_block(s, 188, -50, -105, "minecraft:air")
    # Enquadra o portal com pilares e lanterna
    set_block(s, 188, -50, -107, "minecraft:chiseled_polished_blackstone")
    set_block(s, 188, -49, -107, "minecraft:iron_bars")
    set_block(s, 188, -48, -107, "minecraft:soul_lantern[hanging=false]")

    set_block(s, 188, -50, -104, "minecraft:chiseled_polished_blackstone")
    set_block(s, 188, -49, -104, "minecraft:iron_bars")
    set_block(s, 188, -48, -104, "minecraft:soul_lantern[hanging=false]")

    # -------------------------------------------------------------------------
    # 2. PILARES MONUMENTAIS DE SUSPENSÃO NA LAVA (X=206 e X=226)
    # -------------------------------------------------------------------------
    print("[2/5] Erguendo torres de suspensão industriais no meio do magma...")
    pylon_xs = [206, 226]
    for px in pylon_xs:
        for pz in [-108, -103]:
            # Fundações submersas na lava até o leito (Y=-52 até Y=-64)
            for py in range(-52, -64, -1):
                b = get_block(s, px, py, pz)
                mat = "minecraft:gilded_blackstone" if py % 3 == 0 else "minecraft:chiseled_polished_blackstone"
                set_block(s, px, py, pz, mat)
                if b != "minecraft:air" and "lava" not in b and py < -56:
                    break

            # Pernas da torre acima do nível da ponte (Y=-51 até Y=-41)
            for py in range(-51, -40):
                if py in [-51, -46, -41]:
                    mat = "minecraft:gilded_blackstone"
                elif py == -48:
                    mat = "minecraft:chiseled_polished_blackstone"
                else:
                    mat = "minecraft:polished_blackstone_bricks"
                set_block(s, px, py, pz, mat)

        # Arco superior / viga mestra da torre (Y=-41, Z=-107..-104)
        for z in range(-107, -104):
            set_block(s, px, -41, z, "minecraft:polished_blackstone_brick_slab[type=bottom]")
        set_block(s, px, -40, -106, "minecraft:gilded_blackstone")
        set_block(s, px, -40, -105, "minecraft:gilded_blackstone")
        set_block(s, px, -41, -106, "minecraft:chain[axis=y]")
        set_block(s, px, -42, -106, "minecraft:soul_lantern[hanging=true]")
        set_block(s, px, -41, -105, "minecraft:chain[axis=y]")
        set_block(s, px, -42, -105, "minecraft:soul_lantern[hanging=true]")

        # Cabos tensores de correntes descendo em catenária
        for offset, dy in [(-8, -48), (-6, -46), (-4, -44), (-2, -42),
                           (2, -42), (4, -44), (6, -46), (8, -48)]:
            cx_chain = px + offset
            if 188 <= cx_chain <= 244:
                set_block(s, cx_chain, dy, -107, "minecraft:chain[axis=y]")
                set_block(s, cx_chain, dy, -104, "minecraft:chain[axis=y]")

        play_sound(s, px, -45, -106, "minecraft:block.heavy_core.place", 1.2, 0.9)

    # -------------------------------------------------------------------------
    # 3. CONSTRUÇÃO DO TABULEIRO DA PONTE (X=189 até X=244)
    # -------------------------------------------------------------------------
    print("[3/5] Instalando piso de cobre lapidado e guardas de corrente...")
    for bx in range(188, 245):
        # Piso de caminhada (largura 2 blocos)
        for bz in [-106, -105]:
            set_block(s, bx, -51, bz, "minecraft:weathered_cut_copper_slab[type=top]")

        # Bordas de sustentação lateral
        set_block(s, bx, -51, -107, "minecraft:polished_blackstone_brick_slab[type=top]")
        set_block(s, bx, -51, -104, "minecraft:polished_blackstone_brick_slab[type=top]")

        # Guardas de proteção
        if bx not in pylon_xs:
            set_block(s, bx, -50, -107, "minecraft:chain[axis=x]")
            set_block(s, bx, -50, -104, "minecraft:chain[axis=x]")

        # Candeeiros industriais espaçados a cada 4 blocos
        if bx % 4 == 0 and bx not in pylon_xs:
            set_block(s, bx, -50, -107, "minecraft:iron_bars")
            set_block(s, bx, -49, -107, "minecraft:soul_lantern[hanging=false]")
            set_block(s, bx, -50, -104, "minecraft:iron_bars")
            set_block(s, bx, -49, -104, "minecraft:soul_lantern[hanging=false]")

    # -------------------------------------------------------------------------
    # 4. PLATAFORMA DE CHEGADA / POSTO LESTE (X=244..248, Z=-109..-102)
    # -------------------------------------------------------------------------
    print("[4/5] Ancorando estação de desembarque na margem rochosa leste...")
    # Deck de desembarque nivelado
    for x in range(244, 249):
        for z in range(-108, -103):
            # Preserva os minérios de ouro naturais se estiverem acima
            cur_b = get_block(s, x, -51, z)
            if "ore" not in cur_b:
                set_block(s, x, -51, z, "minecraft:polished_blackstone_bricks")

    # Degraus suaves acompanhando a elevação natural do terreno (Y=-51 -> Y=-50)
    for z in range(-107, -104):
        set_block(s, 246, -50, z, "minecraft:polished_blackstone_brick_stairs[facing=west,half=bottom]")
        set_block(s, 248, -49, z, "minecraft:polished_blackstone_brick_stairs[facing=west,half=bottom]")

    # Braseros / Tochas de alma na margem
    set_block(s, 245, -50, -108, "minecraft:soul_campfire")
    set_block(s, 245, -50, -103, "minecraft:soul_campfire")
    set_block(s, 247, -49, -108, "minecraft:chiseled_polished_blackstone")
    set_block(s, 247, -48, -108, "minecraft:soul_lantern[hanging=false]")
    set_block(s, 247, -49, -103, "minecraft:chiseled_polished_blackstone")
    set_block(s, 247, -48, -103, "minecraft:soul_lantern[hanging=false]")

    # -------------------------------------------------------------------------
    # 5. EFEITOS SONOROS E CELEBRAÇÃO
    # -------------------------------------------------------------------------
    print("[5/5] Finalizando detalhes e partículas...")
    for px in [195, 206, 216, 226, 236, 245]:
        play_sound(s, px, -50, -106, "minecraft:block.chain.place", 1.0, 1.2)
        particle(s, px, -50, -106, "minecraft:soul_fire_flame", 12)

    karlach_say(s, "Ponte concluída! Travessia 100% segura até a margem oposta. O abismo de lava foi domado! 🔥💪")
    s.close()
    print("[✓] Ponte leste construída com sucesso!")

if __name__ == "__main__":
    build_east_bridge()
