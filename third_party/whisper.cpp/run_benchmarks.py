import subprocess
import sys

commands = [
    ("base.en -t 4", "/data/local/tmp/whisper/whisper-cli -m /data/local/tmp/whisper/ggml-base.en.bin -f /data/local/tmp/whisper/jfk.wav -t 4 -nt"),
    ("base.en -t 8", "/data/local/tmp/whisper/whisper-cli -m /data/local/tmp/whisper/ggml-base.en.bin -f /data/local/tmp/whisper/jfk.wav -t 8 -nt"),
    ("small.en -t 4", "/data/local/tmp/whisper/whisper-cli -m /data/local/tmp/whisper/ggml-small.en.bin -f /data/local/tmp/whisper/jfk.wav -t 4 -nt"),
    ("small.en -t 8", "/data/local/tmp/whisper/whisper-cli -m /data/local/tmp/whisper/ggml-small.en.bin -f /data/local/tmp/whisper/jfk.wav -t 8 -nt"),
]

output_path = "/tmp/a2-whisper/a4-phone-runs.txt"

with open(output_path, "w", encoding="utf-8") as out_f:
    for label, cmd in commands:
        print(f"=== Running: {label} ===")
        for run_idx in [1, 2]:
            header = f"\n========================================\nCOMMAND: {cmd}\nRUN: {run_idx} ({'warmup' if run_idx == 1 else 'measured'})\n========================================\n"
            print(header)
            out_f.write(header)
            
            p = subprocess.run(
                ["/opt/homebrew/bin/adb", "shell", cmd],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True
            )
            out_f.write(p.stdout)
            out_f.flush()
            print(p.stdout)
            if p.returncode != 0:
                print(f"ERROR: Exit code {p.returncode}")
                sys.exit(p.returncode)

print(f"Finished all benchmark runs. Output saved to {output_path}")
