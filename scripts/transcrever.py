#!/usr/bin/env python3
import sys
import os
import subprocess
import numpy as np
from faster_whisper import WhisperModel

if len(sys.argv) < 2:
    print("Uso: transcrever.py <arquivo_de_video.mp4> [indice_da_faixa_de_audio]")
    sys.exit(1)

video_path = sys.argv[1]
track_index = int(sys.argv[2]) if len(sys.argv) > 2 else 1  # 1 = Faixa 2 (Voz Limpa)

if not os.path.exists(video_path):
    print(f"Erro: Arquivo não encontrado: {video_path}")
    sys.exit(1)

srt_output = os.path.splitext(video_path)[0] + ".srt"

def load_audio_track(file_path, track_idx):
    cmd = [
        "ffmpeg", "-nostdin", "-threads", "0", "-i", file_path,
        "-map", f"0:a:{track_idx}", "-f", "s16le", "-ac", "1", "-ar", "16000", "-"
    ]
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if not p.stdout:
        return np.array([], dtype=np.float32)
    return np.frombuffer(p.stdout, np.int16).flatten().astype(np.float32) / 32768.0

# Usar modelo local em cache (base tem muito mais precisão que tiny)
if os.path.exists("/tmp/whisper-base"):
    model_name = "/tmp/whisper-base"
elif os.path.exists("/tmp/whisper-tiny"):
    model_name = "/tmp/whisper-tiny"
else:
    model_name = "base"

print(f"Carregando Whisper ({model_name})...")
model = WhisperModel(model_name, device="cpu", compute_type="int8")

glossario = (
    "Transcrição de engenharia de software sobre Minecraft e Inteligência Artificial. "
    "Palavras técnicas: Mineflayer, PrismarineJS, Voyager, craft-agents, Clojure, Java 21, JVM, Node.js, "
    "V8, ProtoDef, PathSolver3D, Behavior Tree, Malli, MCP, Model Context Protocol, Karlach, "
    "EDN, eval(), tick, Netty, EmbeddedChannel, A*, Virtual Threads, heap, leases, Blackboard, "
    "Headless, JEG, Just Enough Guns, Naturalist."
)

print(f"Extraindo Faixa {track_index + 1} de áudio (Voz)...")
audio = load_audio_track(video_path, track_index)

if len(audio) == 0 and track_index != 0:
    print(f"Faixa {track_index + 1} não encontrada ou vazia. Tentando Faixa 1 (Mix Geral)...")
    track_index = 0
    audio = load_audio_track(video_path, track_index)

print(f"Áudio pronto ({len(audio)/16000:.2f}s). Transcrevendo em PT-BR...")
segments, info = model.transcribe(
    audio,
    language="pt",
    initial_prompt=glossario,
    word_timestamps=True,
    beam_size=5,
    vad_filter=False
)

results = []
for seg in segments:
    print(f"[{seg.start:.2f}s -> {seg.end:.2f}s] {seg.text.strip()}")
    results.append(seg)

def format_timestamp(seconds: float) -> str:
    millis = int((seconds % 1) * 1000)
    seconds = int(seconds)
    mins, secs = divmod(seconds, 60)
    hours, mins = divmod(mins, 60)
    return f"{hours:02d}:{mins:02d}:{secs:02d},{millis:03d}"

with open(srt_output, "w", encoding="utf-8") as f:
    for i, seg in enumerate(results, start=1):
        start = format_timestamp(seg.start)
        end = format_timestamp(seg.end)
        f.write(f"{i}\n{start} --> {end}\n{seg.text.strip()}\n\n")

print(f"\nLegenda salva com sucesso em:\n{srt_output}")
