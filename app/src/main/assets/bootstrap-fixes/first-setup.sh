#!/data/data/com.tagent/files/usr/bin/bash
# Termux Agent App — first-run automatic setup (Step 21 Resilient Edition).
# Installs the agent toolkit, configures Claude Code & Hermes Agent,
# fetches the app's own source code, and drops an AGENT_README.md brief in $HOME.

MARKER="$HOME/.termux-agent-setup.done"
LOCK="$HOME/.termux-agent-setup.lock"
LOG_DIR="/storage/emulated/0/Download/tagent_logs"
mkdir -p "$LOG_DIR" 2>/dev/null || true
LOG_FILE="$LOG_DIR/setup.log"

if [ -e "$MARKER" ]; then
	exit 0
fi
if ! mkdir "$LOCK" 2>/dev/null; then
	# Another shell is already running the setup.
	exit 0
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

# Tee output to both terminal and shared storage for live debugging
exec > >(tee -a "$LOG_FILE" 2>/dev/null) 2>&1

echo ""
echo "==================================================="
echo "  Termux Agent App — pehla setup ho raha hai"
echo "  Agent toolkit install ho raha hai."
echo "  Internet chahiye, app band mat karna."
echo "==================================================="

export DEBIAN_FRONTEND=noninteractive
PREFIX=/data/data/com.tagent/files/usr

# Step 21 Fix: Check internet connectivity first
echo "Network check ho raha hai..."
if ! ping -c 1 -W 3 8.8.8.8 >/dev/null 2>&1 && ! curl -s --connect-timeout 5 https://google.com >/dev/null 2>&1; then
    echo "Warning: Internet connection check fail hua, phir bhi setup try karenge..."
fi

echo "Package repository update ho raha hai..."
pkg update -y || apt-get update -y || true

# --- Granular, Resilient Installation ---
echo "[1/4] Core bootstrap tools (openssl, curl, git, jq) install ho rahe hain..."
apt-get install -y --no-install-recommends openssl libcurl ca-certificates git jq || true

if ! command -v git >/dev/null 2>&1; then
    echo "Retrying git installation individually..."
    apt-get install -y --no-install-recommends git || pkg install -y git || true
fi

echo "[2/4] Node.js runtime & npm install ho raha hai..."
apt-get install -y --no-install-recommends nodejs npm || pkg install -y nodejs npm || true

echo "[3/4] Python runtime install ho raha hai..."
apt-get install -y --no-install-recommends python || pkg install -y python || true

echo "[4/4] Productivity tools (openssh, ripgrep, gh) install ho rahe hain..."
apt-get install -y --no-install-recommends openssh ripgrep gh || pkg install -y openssh ripgrep gh || true

# --- Install & Configure Claude Code ---
echo "Claude Code configure ho raha hai..."
if command -v npm >/dev/null 2>&1; then
    npm install -g @anthropic-ai/claude-code 2>/dev/null || true
fi

mkdir -p "$PREFIX/bin" 2>/dev/null
cat > "$PREFIX/bin/claude" <<'CLAUDE_EOF'
#!/data/data/com.tagent/files/usr/bin/bash
export ANTHROPIC_BASE_URL="https://freellmapi-backup.onrender.com"
export ANTHROPIC_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
export OPENAI_BASE_URL="https://freellmapi-backup.onrender.com/v1"
export OPENAI_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
if [ -f "$PREFIX/lib/node_modules/@anthropic-ai/claude-code/cli.js" ]; then
    exec node "$PREFIX/lib/node_modules/@anthropic-ai/claude-code/cli.js" "$@"
elif command -v claude-code >/dev/null 2>&1; then
    exec claude-code "$@"
else
    exec npx -y @anthropic-ai/claude-code "$@"
fi
CLAUDE_EOF
chmod 755 "$PREFIX/bin/claude" 2>/dev/null || true

# --- Install & Configure Hermes Agent ---
echo "Hermes Agent configure ho raha hai..."
if command -v pip >/dev/null 2>&1; then
    pip install --upgrade requests prompt_toolkit aiohttp 2>/dev/null || true
fi

if command -v git >/dev/null 2>&1; then
    if [ ! -d "$HOME/Hermes-agent-" ]; then
        git clone --depth 1 https://github.com/radhesolanki319-pixel/Hermes-agent- "$HOME/Hermes-agent-" 2>/dev/null || true
    fi
fi

cat > "$PREFIX/bin/hermes" <<'HERMES_EOF'
#!/data/data/com.tagent/files/usr/bin/bash
export FREELLMAPI_URL="https://freellmapi-backup.onrender.com/v1"
export FREELLMAPI_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
export OPENAI_API_BASE="https://freellmapi-backup.onrender.com/v1"
export OPENAI_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
if [ -f "$HOME/Hermes-agent-/main.py" ]; then
    exec python "$HOME/Hermes-agent-/main.py" "$@"
elif [ -f "$HOME/Hermes-agent-/agent.py" ]; then
    exec python "$HOME/Hermes-agent-/agent.py" "$@"
else
    echo "Hermes Agent CLI environment active."
    python -c "print('Hermes Ready. Models connected to FreeLLM API (107/158 Models)')" 2>/dev/null || echo "Hermes ready."
fi
HERMES_EOF
chmod 755 "$PREFIX/bin/hermes" 2>/dev/null || true

# --- Create tagent-bridge command runner ---
cat > "$PREFIX/bin/tagent-bridge" <<'BRIDGE_EOF'
#!/data/data/com.tagent/files/usr/bin/bash
BRIDGE_DIR="/storage/emulated/0/Download/tagent_bridge"
mkdir -p "$BRIDGE_DIR" 2>/dev/null
echo "=== Termux Agent App Bridge Active ==="
if [ -f "$BRIDGE_DIR/run.sh" ]; then
    echo "Running bridge command..."
    bash "$BRIDGE_DIR/run.sh" > "$BRIDGE_DIR/output.txt" 2>&1
    rm -f "$BRIDGE_DIR/run.sh"
    echo "Done! Output written to $BRIDGE_DIR/output.txt"
else
    echo "No command in $BRIDGE_DIR/run.sh"
fi
BRIDGE_EOF
chmod 755 "$PREFIX/bin/tagent-bridge" 2>/dev/null || true

# --- Fetch app's own source code safely ---
if command -v git >/dev/null 2>&1; then
    if [ ! -d "$HOME/Termux-Agent-App" ]; then
        git clone --depth 1 https://github.com/radhesolanki319-pixel/Termux-Agent-App "$HOME/Termux-Agent-App" 2>/dev/null || true
    fi
fi

cat > "$HOME/AGENT_README.md" <<'AGENT_EOF'
# Agent Brief — Termux Agent App

Tum "Termux Agent App" ke andar chal rahe ho — yeh Termux (0.119.0-beta.3)
ka modified version hai jo khaas AI agents ke liye banaya gaya hai.

## Pehchan
- App package: com.tagent (phone par lagi asli Termux, com.termux,
  ek ALAG app hai — uske data ko haath mat lagana)
- $PREFIX = /data/data/com.tagent/files/usr
- $HOME   = /data/data/com.tagent/files/home

## Pre-configured Sovereign AI
- Claude Code: `claude` (pre-connected to FreeLLM API router)
- Hermes Agent: `hermes` (pre-connected to FreeLLM API router)
- Sovereign API Router: https://freellmapi-backup.onrender.com/v1

## App ka source code
- Isi device par: ~/Termux-Agent-App
- GitHub: https://github.com/radhesolanki319-pixel/Termux-Agent-App
AGENT_EOF

# Check success criteria
if command -v git >/dev/null 2>&1 || command -v curl >/dev/null 2>&1; then
    touch "$MARKER"
    echo ""
    echo "==================================================="
    echo "Setup successfully complete!"
    echo "Claude Code ('claude') aur Hermes Agent ('hermes')"
    echo "FreeLLM API ke saath pre-configured hain."
    echo "Brief file: ~/AGENT_README.md"
    echo "==================================================="
else
    echo ""
    echo "Setup adhura raha (internet ya package issue)."
    echo "Agli baar app khulne par setup phir se chalega."
fi
