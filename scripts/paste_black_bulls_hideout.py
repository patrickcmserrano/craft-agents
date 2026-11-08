#!/usr/bin/env python3
"""
Black Bulls Hideout Sovereign Constructor
Imprime e nivela o lendário castelo dos Touros Negros (Black Clover)
com fachada voltada para o Norte em frente aos jogadores.
"""

import gzip
import struct
import socket
import time
import sys

SCHEMATIC_PATH = "/home/pace/Downloads/blackbullshideoutcompleted.schem"
BRIDGE_HOST = "127.0.0.1"
BRIDGE_PORT = 25595

# Anchor coordinates in Minecraft world
# Snout center in schematic is X=52, Z=9.
# Players are at X ~ -40, Z ~ 165.
# With X0 = -92, Z0 = 170, Y0 = 64:
# Castle spans: X in [-92, 26], Z in [170, 270], Y in [64, 203].
# The snout is at X = -40, Z = 179 (facing North towards players at Z = 165).
ORIGIN_X = -92
ORIGIN_Y = 64
ORIGIN_Z = 170

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

def play_sound(s, x, y, z, sound="minecraft:block.anvil.use", vol=1.0, pitch=1.0):
    send_cmd(s, f"playsound {sound} master @a {x} {y} {z} {vol:.1f} {pitch:.1f}")

def particle(s, x, y, z, p="minecraft:flame", count=10):
    send_cmd(s, f"particle {p} {x:.1f} {y:.1f} {z:.1f} 0.5 0.5 0.5 0.05 {count} normal")

def parse_schematic(path):
    print(f"[*] Lendo schematic de {path}...")
    with gzip.open(path, "rb") as f:
        data = f.read()

    offset = 0
    def r_b():
        nonlocal offset
        b = data[offset]
        offset += 1
        return b

    def r_s():
        nonlocal offset
        v = struct.unpack(">h", data[offset:offset+2])[0]
        offset += 2
        return v

    def r_i():
        nonlocal offset
        v = struct.unpack(">i", data[offset:offset+4])[0]
        offset += 4
        return v

    def r_str():
        nonlocal offset
        l = struct.unpack(">H", data[offset:offset+2])[0]
        offset += 2
        s = data[offset:offset+l].decode("utf-8", errors="replace")
        offset += l
        return s

    def r_p(tt):
        nonlocal offset
        if tt == 0: return None
        if tt == 1: return r_b()
        if tt == 2: return r_s()
        if tt == 3: return r_i()
        if tt == 4: offset += 8; return 0
        if tt == 5: offset += 4; return 0
        if tt == 6: offset += 8; return 0
        if tt == 7:
            l = r_i(); b = data[offset:offset+l]; offset += l; return b
        if tt == 8: return r_str()
        if tt == 9:
            et = r_b(); l = r_i()
            return [r_p(et) for _ in range(l)]
        if tt == 10:
            res = {}
            while True:
                t = r_b()
                if t == 0: break
                n = r_str()
                res[n] = r_p(t)
            return res
        if tt == 11: l = r_i(); offset += 4*l; return []
        if tt == 12: l = r_i(); offset += 8*l; return []
        raise ValueError(f"Tag desconhecida {tt}")

    tt = r_b()
    r_str()
    root = r_p(tt)

    W = root["Width"]
    H = root["Height"]
    L = root["Length"]
    palette = root["Palette"]
    raw_bytes = root["BlockData"]

    id_to_name = {v: k for k, v in palette.items()}

    print(f"[*] Descompactando {W}x{H}x{L} = {W*H*L} blocos...")
    raw = []
    idx = 0
    d_len = len(raw_bytes)
    while idx < d_len:
        v = 0; s = 0
        while True:
            b = raw_bytes[idx]; idx += 1
            v |= (b & 0x7F) << s
            if not (b & 0x80): break
            s += 7
        raw.append(v)

    return W, H, L, id_to_name, raw

def level_terrain(s, W, L, H):
    print("[1/3] Nivelando terreno e preparando fundação...")
    karlach_say(s, "Iniciando nivelamento de terreno e assentamento da fundação dos Touros Negros! 🚜 ⚒️")
    play_sound(s, -40, 68, 175, "minecraft:entity.iron_golem.repair", 1.2, 0.9)

    min_x = ORIGIN_X
    max_x = ORIGIN_X + W - 1
    min_z = ORIGIN_Z
    max_z = ORIGIN_Z + L - 1

    # Preencher depressões e água abaixo de Y0 com terra
    step = 20
    for x in range(min_x, max_x + 1, step):
        x2 = min(x + step - 1, max_x)
        for z in range(min_z, max_z + 1, step):
            z2 = min(z + step - 1, max_z)
            send_cmd(s, f"fill {x} 55 {z} {x2} {ORIGIN_Y - 1} {z2} dirt replace water")
            send_cmd(s, f"fill {x} 55 {z} {x2} {ORIGIN_Y - 1} {z2} dirt replace air")

    # Limpar vegetação, árvores e terra acima de Y0 até Y0 + 55 (Y=120)
    for x in range(min_x, max_x + 1, step):
        x2 = min(x + step - 1, max_x)
        for z in range(min_z, max_z + 1, step):
            z2 = min(z + step - 1, max_z)
            for y in range(ORIGIN_Y, ORIGIN_Y + 55, 18):
                y2 = min(y + 17, ORIGIN_Y + 54)
                send_cmd(s, f"fill {x} {y} {z} {x2} {y2} {z2} air")

    # Limpar linha de visão na frente dos jogadores (X: -60 a -20, Z: 160 a 170, Y: 68 a 82)
    send_cmd(s, "fill -60 68 160 -20 85 170 air replace oak_leaves")
    send_cmd(s, "fill -60 68 160 -20 85 170 air replace oak_log")
    send_cmd(s, "fill -60 68 160 -20 85 170 air replace birch_leaves")
    send_cmd(s, "fill -60 68 160 -20 85 170 air replace birch_log")

    particle(s, -40, 68, 175, "minecraft:poof", 30)
    play_sound(s, -40, 68, 175, "minecraft:block.stone.break", 1.0, 1.0)
    print("      Fundação nivelada e espaço aéreo desobstruído com sucesso.")

def generate_commands(W, H, L, id_to_name, raw):
    print("[2/3] Compilando comandos com compressão RLE por camada...")
    air_ids = set([bid for bid, name in id_to_name.items() if "air" in name or "structure_void" in name])

    layers = [[] for _ in range(H)]
    total_cmds = 0
    total_blocks = 0

    for y in range(H):
        y_cmds = []
        for z in range(L):
            curr_b = None
            run_start_x = None
            for x in range(W):
                bid = raw[(y * L + z) * W + x]
                if bid in air_ids:
                    if curr_b is not None:
                        run_len = x - run_start_x
                        b_name = id_to_name[curr_b]
                        wx1 = ORIGIN_X + run_start_x
                        wx2 = ORIGIN_X + x - 1
                        wy = ORIGIN_Y + y
                        wz = ORIGIN_Z + z
                        total_blocks += run_len
                        if run_len > 1:
                            y_cmds.append(f"fill {wx1} {wy} {wz} {wx2} {wy} {wz} {b_name} replace")
                        else:
                            y_cmds.append(f"setblock {wx1} {wy} {wz} {b_name} replace")
                        curr_b = None
                        run_start_x = None
                else:
                    if curr_b == bid:
                        pass
                    else:
                        if curr_b is not None:
                            run_len = x - run_start_x
                            b_name = id_to_name[curr_b]
                            wx1 = ORIGIN_X + run_start_x
                            wx2 = ORIGIN_X + x - 1
                            wy = ORIGIN_Y + y
                            wz = ORIGIN_Z + z
                            total_blocks += run_len
                            if run_len > 1:
                                y_cmds.append(f"fill {wx1} {wy} {wz} {wx2} {wy} {wz} {b_name} replace")
                            else:
                                y_cmds.append(f"setblock {wx1} {wy} {wz} {b_name} replace")
                        curr_b = bid
                        run_start_x = x

            if curr_b is not None:
                run_len = W - run_start_x
                b_name = id_to_name[curr_b]
                wx1 = ORIGIN_X + run_start_x
                wx2 = ORIGIN_X + W - 1
                wy = ORIGIN_Y + y
                wz = ORIGIN_Z + z
                total_blocks += run_len
                if run_len > 1:
                    y_cmds.append(f"fill {wx1} {wy} {wz} {wx2} {wy} {wz} {b_name} replace")
                else:
                    y_cmds.append(f"setblock {wx1} {wy} {wz} {b_name} replace")

        layers[y] = y_cmds
        total_cmds += len(y_cmds)

    print(f"      Total de blocos válidos: {total_blocks}")
    print(f"      Comandos otimizados gerados: {total_cmds}")
    return layers, total_cmds, total_blocks

def print_castle(s, layers, total_cmds):
    print("[3/3] Materializando castelo camada por camada...")
    karlach_say(s, "Fundação pronta! Erguendo as torres e a imensa caveira do Touro Negro! 🐂 🏰")

    reader = s.makefile('r', encoding='utf-8')
    executed = 0
    t_start = time.time()
    last_say_time = t_start
    BATCH_SIZE = 100

    for y, y_cmds in enumerate(layers):
        if not y_cmds:
            continue

        # Pipeline commands in batches for ultra-high throughput (14,000+ cmds/s)
        for i in range(0, len(y_cmds), BATCH_SIZE):
            chunk = y_cmds[i:i + BATCH_SIZE]
            buf = "".join([f"CMD|{c}\n" for c in chunk])
            s.sendall(buf.encode("utf-8"))
            for _ in range(len(chunk)):
                reader.readline()
            executed += len(chunk)

        pct = (executed / total_cmds) * 100.0
        now = time.time()
        if now - last_say_time > 1.5:
            rate = executed / max(now - t_start, 0.001)
            sys.stdout.write(f"\r      Progresso: {executed}/{total_cmds} cmds ({pct:.1f}%) | Camada {y}/{len(layers)} | {rate:.0f} cmds/s")
            sys.stdout.flush()
            play_sound(s, -40, ORIGIN_Y + y, 175, "minecraft:block.stone.place", 0.7, 1.2)
            particle(s, -40, ORIGIN_Y + y, 175, "minecraft:flame", 8)
            last_say_time = now

    t_total = time.time() - t_start
    print(f"\n      Construção concluída em {t_total:.1f} segundos ({total_cmds / max(t_total, 0.001):.0f} cmds/s)!")

    # Grand finale
    karlach_say(s, "🎉 O Esconderijo dos Touros Negros (Black Bulls Hideout) foi erguido em toda a sua glória! Fachada voltada para o Norte! 🐂 🏰 ⚔️")
    play_sound(s, -40, 75, 175, "minecraft:ui.toast.challenge_complete", 1.5, 1.0)
    play_sound(s, -40, 75, 175, "minecraft:block.beacon.activate", 1.5, 1.0)
    for _ in range(5):
        particle(s, -40, 75, 175, "minecraft:totem_of_undying", 40)
        particle(s, -40, 75, 175, "minecraft:firework", 30)

def main():
    print("=" * 60)
    print("  BLACK BULLS HIDEOUT - CONSTRUTOR AUTÔNOMO SOBERANO")
    print("=" * 60)

    W, H, L, id_to_name, raw = parse_schematic(SCHEMATIC_PATH)
    layers, total_cmds, total_blocks = generate_commands(W, H, L, id_to_name, raw)

    s = connect_bridge()
    try:
        level_terrain(s, W, L, H)
        print_castle(s, layers, total_cmds)
    finally:
        s.close()
    print("=" * 60)
    print("  OPERAÇÃO 100% CONCLUÍDA")
    print("=" * 60)

if __name__ == "__main__":
    main()
