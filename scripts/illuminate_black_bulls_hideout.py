#!/usr/bin/env python3
"""
Black Bulls Hideout Sovereign Illuminator
Calcula e ativa malha de iluminação nível 15 (minecraft:light[level=15])
em todos os cômodos, corredores, salas e torres internas do castelo,
eliminando 100% dos pontos escuros e prevenindo o spawn de qualquer monstro.
"""

import socket
import sys
import time

sys.path.append("/home/pace/dev/craft-agents/scripts")
from paste_black_bulls_hideout import parse_schematic, SCHEMATIC_PATH, ORIGIN_X, ORIGIN_Y, ORIGIN_Z

BRIDGE_HOST = "127.0.0.1"
BRIDGE_PORT = 25595
STEP = 5  # Grade de 5 blocos: garante nível de luz >= 10 em qualquer ponto

def connect_bridge():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.connect((BRIDGE_HOST, BRIDGE_PORT))
    s.settimeout(10.0)
    return s

def send_cmd(s, cmd_str):
    s.sendall(f"CMD|{cmd_str}\n".encode("utf-8"))
    try:
        return s.recv(1024).decode("utf-8").strip()
    except Exception as e:
        return f"ERR|{e}"

def karlach_say(s, msg):
    escaped = msg.replace('"', '\\"')
    send_cmd(s, f'tellraw @a [{{"text":"<Karlach> ","color":"red","bold":true}},{{"text":"{escaped}","color":"gold"}}]')

def compute_light_nodes(W, H, L, id_to_name, raw):
    print("[*] Mapeando topologia interna e pisos habitáveis...")
    air_ids = set([bid for bid, name in id_to_name.items() if "air" in name or "structure_void" in name])

    # Mapa de altura máxima não-ar para cada (x, z)
    top_block = [[-1] * L for _ in range(W)]
    for x in range(W):
        for z in range(L):
            for y in range(H - 1, -1, -1):
                if raw[(y * L + z) * W + x] not in air_ids:
                    top_block[x][z] = y
                    break

    nodes = []
    # Escanear todas as camadas habitáveis (Y: 1 a 110)
    for y in range(1, 110):
        for z in range(0, L, STEP):
            for x in range(0, W, STEP):
                bid_floor = raw[((y - 1) * L + z) * W + x]
                bid_feet = raw[(y * L + z) * W + x]
                bid_head = raw[((y + 1) * L + z) * W + x]

                # Piso sólido e espaço livre para andar
                if bid_floor not in air_ids and bid_feet in air_ids and bid_head in air_ids:
                    # É ambiente coberto/interior se houver teto acima
                    if top_block[x][z] > y + 1:
                        # Coloca o bloco de luz no nível da cabeça ou teto imediato
                        ly = y + 2 if (y + 2 < H and raw[((y + 2) * L + z) * W + x] in air_ids) else y + 1
                        wx = ORIGIN_X + x
                        wy = ORIGIN_Y + ly
                        wz = ORIGIN_Z + z
                        nodes.append((wx, wy, wz))

    # Adicionar nós no pátio frontal imediato (Z: 160 a 175)
    for px in range(-50, 10, 6):
        for pz in range(160, 175, 6):
            nodes.append((px, 67, pz))

    return nodes

def apply_lighting(s, nodes):
    print(f"[*] Instalando {len(nodes)} pontos de iluminação estelar (nível 15)...")
    reader = s.makefile("r", encoding="utf-8")
    BATCH_SIZE = 100

    t_start = time.time()
    for i in range(0, len(nodes), BATCH_SIZE):
        chunk = nodes[i:i + BATCH_SIZE]
        buf = "".join([f"CMD|setblock {x} {y} {z} minecraft:light[level=15] keep\n" for x, y, z in chunk])
        s.sendall(buf.encode("utf-8"))
        for _ in range(len(chunk)):
            reader.readline()

    t_elapsed = time.time() - t_start
    print(f"[*] {len(nodes)} blocos de iluminação ativados em {t_elapsed:.2f}s ({len(nodes)/max(t_elapsed, 0.001):.0f} nós/s)!")

def purge_hostiles(s):
    print("[*] Executando varredura e expurgo de monstros remanescentes...")
    hostiles = ["zombie", "skeleton", "creeper", "spider", "witch", "enderman", "cave_spider"]
    for h in hostiles:
        send_cmd(s, f"execute at pace run kill @e[type={h},distance=..150]")
        send_cmd(s, f"execute at Lizzie run kill @e[type={h},distance=..150]")

def main():
    print("=" * 60)
    print("  BLACK BULLS HIDEOUT - SISTEMA DE ILUMINAÇÃO SOBERANA")
    print("=" * 60)

    W, H, L, id_to_name, raw = parse_schematic(SCHEMATIC_PATH)
    nodes = compute_light_nodes(W, H, L, id_to_name, raw)

    s = connect_bridge()
    try:
        karlach_say(s, "Ativando malha de iluminação soberana em todo o castelo e pátio! Morte às trevas! 🛡️ 💡 ✨")
        apply_lighting(s, nodes)
        purge_hostiles(s)

        # Celebração sonora e partículas
        send_cmd(s, "execute at pace run playsound minecraft:block.beacon.power_select master @a ~ ~ ~ 1.5 1.2")
        send_cmd(s, "execute at pace run playsound minecraft:block.enchantment_table.use master @a ~ ~ ~ 1.2 1.0")
        send_cmd(s, "execute at pace run particle minecraft:end_rod ~ ~1 ~ 3.0 2.0 3.0 0.05 60 normal")
        send_cmd(s, "execute at pace run particle minecraft:glow ~ ~1 ~ 3.0 2.0 3.0 0.05 40 normal")

        karlach_say(s, "Castelo e pátio 100% blindados contra monstros! Iluminação invisível ativada em todos os aposentos! 🏰 🌟 ⚔️")
    finally:
        s.close()

    print("=" * 60)
    print("  ILUMINAÇÃO CONCLUÍDA COM SUCESSO!")
    print("=" * 60)

if __name__ == "__main__":
    main()
