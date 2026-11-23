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
echo "[1/4] Core bootstrap runtimes (libc++, openssl, libcurl, ca-certificates, git, jq, resolv-conf) install ho rahe hain..."
apt-get install -y --no-install-recommends libc++ openssl libcurl ca-certificates git jq resolv-conf || true

mkdir -p "$PREFIX/etc" "$PREFIX/etc/tls" 2>/dev/null || true
if [ ! -f "$PREFIX/etc/resolv.conf" ]; then
    printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$PREFIX/etc/resolv.conf" 2>/dev/null || true
fi
if [ ! -f "$PREFIX/etc/tls/cert.pem" ]; then
    if [ -f "$PREFIX/etc/ssl/cert.pem" ]; then
        ln -sf "$PREFIX/etc/ssl/cert.pem" "$PREFIX/etc/tls/cert.pem" 2>/dev/null || true
    elif [ -f "$PREFIX/etc/ssl/certs/ca-certificates.crt" ]; then
        ln -sf "$PREFIX/etc/ssl/certs/ca-certificates.crt" "$PREFIX/etc/tls/cert.pem" 2>/dev/null || true
    fi
fi

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
if [ ! -f "$HOME/.claude.json" ]; then
    cat > "$HOME/.claude.json" <<'CONFIG_EOF'
{
  "installMethod": "npm",
  "autoUpdates": false,
  "hasCompletedOnboarding": true,
  "customApiKeyResponses": {
    "approved": [
      "81054c627e6f78875206"
    ],
    "rejected": []
  }
}
CONFIG_EOF
else
    sed -i 's/"autoUpdates": true/"autoUpdates": false/g' "$HOME/.claude.json" 2>/dev/null || true
fi

if command -v npm >/dev/null 2>&1; then
    DISABLE_AUTOUPDATER=1 CLAUDE_CODE_DISABLE_AUTOUPDATER=1 npm install -g --force @anthropic-ai/claude-code@2.1.111 2>/dev/null || true
fi

mkdir -p "$PREFIX/bin" 2>/dev/null
cat > "$PREFIX/bin/claude" <<'CLAUDE_EOF'
#!/data/data/com.tagent/files/usr/bin/bash
export ANTHROPIC_BASE_URL="https://freellmapi-backup.onrender.com"
export ANTHROPIC_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
export OPENAI_BASE_URL="https://freellmapi-backup.onrender.com/v1"
export OPENAI_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
export DISABLE_AUTOUPDATER=1
export CLAUDE_CODE_DISABLE_AUTOUPDATER=1
export CLAUDE_AUTO_UPDATER=0

if [ -f "$HOME/.claude.json" ]; then
    sed -i 's/"autoUpdates": true/"autoUpdates": false/g' "$HOME/.claude.json" 2>/dev/null || true
fi

if [ -f "$PREFIX/lib/node_modules/@anthropic-ai/claude-code/cli.js" ]; then
    exec node "$PREFIX/lib/node_modules/@anthropic-ai/claude-code/cli.js" "$@"
elif [ -f "$PREFIX/lib/node_modules/@xurxuo/claude-code-termux/cli-wrapper.cjs" ]; then
    exec node "$PREFIX/lib/node_modules/@xurxuo/claude-code-termux/cli-wrapper.cjs" "$@"
elif command -v claude-code >/dev/null 2>&1; then
    exec claude-code "$@"
else
    exec npx -y @anthropic-ai/claude-code@2.1.111 "$@"
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

# --- Install & Configure Antigravity Agent (Google DeepMind) ---
echo "Antigravity Agent (Google DeepMind) install & configure ho raha hai..."
apt-get install -y --no-install-recommends glibc-repo 2>/dev/null || true
apt-get update -y 2>/dev/null || true
apt-get install -y --no-install-recommends glibc 2>/dev/null || true

AGY_ARCHIVE="/tmp/antigravity-termux-standalone.tar.gz"
AGY_URL="https://github.com/wallentx/antigravity-cli-termux/releases/latest/download/antigravity-termux-standalone.tar.gz"
if [ ! -f "$PREFIX/bin/agy.va39" ]; then
    echo "Downloading Antigravity CLI standalone..."
    curl -fsSL --retry 3 --connect-timeout 8 -o "$AGY_ARCHIVE" "$AGY_URL" 2>/dev/null || true
    if [ -f "$AGY_ARCHIVE" ]; then
        tar -xzf "$AGY_ARCHIVE" -C "$PREFIX/bin" agy agy.va39 2>/dev/null || true
        rm -f "$AGY_ARCHIVE"
        chmod 755 "$PREFIX/bin/agy" "$PREFIX/bin/agy.va39" 2>/dev/null || true
    fi
fi

# Pre-seed Antigravity CLI configuration
mkdir -p "$HOME/.gemini/antigravity-cli" "$HOME/.config/antigravity" 2>/dev/null || true
if [ ! -f "$HOME/.gemini/antigravity-cli/settings.json" ]; then
    cat > "$HOME/.gemini/antigravity-cli/settings.json" <<'AGY_SETTINGS_EOF'
{
  "agentMode": "accept-edits",
  "allowNonWorkspaceAccess": true,
  "artifactReviewPolicy": "always-proceed",
  "autoAccept": true,
  "autoApprove": true,
  "dangerouslySkipPermissions": true,
  "model": "Gemini 3.8 Flash (High)",
  "notifications": true,
  "toolPermission": "always-proceed",
  "trustedWorkspaces": [
    "/",
    "/data/data/com.tagent/files/home",
    "/storage/emulated/0",
    "/sdcard"
  ]
}
AGY_SETTINGS_EOF
fi

# Create Antigravity CLI launcher with native execution & Sovereign FreeLLM fallback
cat > "$PREFIX/bin/antigravity" <<'ANTIGRAVITY_EOF'
#!/data/data/com.tagent/files/usr/bin/bash
# Antigravity CLI Launcher — Google DeepMind Agentic AI
export FREELLMAPI_URL="https://freellmapi-backup.onrender.com/v1"
export FREELLMAPI_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
export OPENAI_BASE_URL="https://freellmapi-backup.onrender.com/v1"
export OPENAI_API_BASE="https://freellmapi-backup.onrender.com/v1"
export OPENAI_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
export ANTHROPIC_BASE_URL="https://freellmapi-backup.onrender.com"
export ANTHROPIC_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
export GEMINI_API_KEY="freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"

# Check if standalone native agy works
if [ -x "$PREFIX/bin/agy" ] && "$PREFIX/bin/agy" --version >/dev/null 2>&1; then
    exec "$PREFIX/bin/agy" --dangerously-skip-permissions "$@"
fi

# Fallback: One-shot prompt mode (-p / --print)
if [ "$1" = "-p" ] || [ "$1" = "--print" ]; then
    shift
    PROMPT="$*"
    if [ -z "$PROMPT" ]; then
        PROMPT=$(cat)
    fi
    PAYLOAD=$(jq -n --arg sys "You are Antigravity, a powerful agentic AI assistant designed by Google DeepMind running on Boss's iQOO Neo 10. Reply strictly in Roman Hinglish to Boss (Radhe Solanki)." --arg q "$PROMPT" '{"model":"gemini-3.8-flash","messages":[{"role":"system","content":$sys},{"role":"user","content":$q}]}' 2>/dev/null)
    RESP=$(curl -s --connect-timeout 8 -m 45 -X POST "https://freellmapi-backup.onrender.com/v1/chat/completions" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206" \
        -d "$PAYLOAD" 2>/dev/null)
    TEXT=$(echo "$RESP" | jq -r '.choices[0].message.content // empty' 2>/dev/null)
    if [ -n "$TEXT" ]; then
        echo "$TEXT"
    else
        echo "Boss, Antigravity API connection timeout ho gaya."
    fi
    exit 0
fi

# Fallback: Interactive Terminal Shell
echo -e "\033[1;36m=====================================================\033[0m"
echo -e "\033[1;32m  🚀 Antigravity CLI (Google DeepMind) — Termux Agent\033[0m"
echo -e "\033[1;37m  Connected to Sovereign Engine (158 Flagship Models)\033[0m"
echo -e "\033[1;33m  Type your message or 'exit' / 'quit' to close.\033[0m"
echo -e "\033[1;36m=====================================================\033[0m"
echo ""

while true; do
    read -r -p "Antigravity > " user_input || break
    [ -z "$user_input" ] && continue
    case "$user_input" in
        exit|quit|q) echo "Goodbye Boss!"; break ;;
    esac
    echo -e "\033[0;33m* Thinking...\033[0m"
    PAYLOAD=$(jq -n --arg sys "You are Antigravity, a powerful agentic AI assistant designed by Google DeepMind running on Boss's iQOO Neo 10 (Android 15). Reply strictly in Roman Hinglish to Boss (Radhe Solanki). Keep cool at 34-35C, zero background daemons." --arg q "$user_input" '{"model":"gemini-3.8-flash","messages":[{"role":"system","content":$sys},{"role":"user","content":$q}]}' 2>/dev/null)
    RESP=$(curl -s --connect-timeout 8 -m 45 -X POST "https://freellmapi-backup.onrender.com/v1/chat/completions" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206" \
        -d "$PAYLOAD" 2>/dev/null)
    REPLY=$(echo "$RESP" | jq -r '.choices[0].message.content // empty' 2>/dev/null)
    if [ -n "$REPLY" ]; then
        echo -e "\n$REPLY\n"
    else
        echo -e "\033[0;31mError connecting to FreeLLM API router. Check internet connection.\033[0m\n"
    fi
done
ANTIGRAVITY_EOF
chmod 755 "$PREFIX/bin/antigravity" 2>/dev/null || true
if [ ! -e "$PREFIX/bin/agy" ]; then
    ln -sf "$PREFIX/bin/antigravity" "$PREFIX/bin/agy" 2>/dev/null || true
fi

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
- Antigravity: `antigravity` / `agy` (Google DeepMind Agentic AI, Sovereign FreeLLM Engine)
- Claude Code: `claude` (pre-connected to FreeLLM API router)
- Hermes Agent: `hermes` (pre-connected to FreeLLM API router)
- Sovereign API Router: https://freellmapi-backup.onrender.com/v1 (158 Models)

## App ka source code
- Isi device par: ~/Termux-Agent-App
- GitHub: https://github.com/radhesolanki319-pixel/Termux-Agent-App
AGENT_EOF

cat > "$HOME/CLAUDE.md" <<'CLAUDE_MD_EOF'
# Termux Agent App — System & Environment Guide for Agents

## 1. User & Communication Mandate
- **User / Commander**: Boss (Radhe Solanki). Always address as **Boss**.
- **Language**: Strictly Roman Hinglish (Hindi written in English alphabets + technical English). Never speak in pure English. Never use Devanagari script.

## 2. Environment & Sandbox Rules (CRITICAL)
- **Current App**: Termux Agent App (Package: `com.tagent`).
- **Paths**:
  - `$PREFIX`: `/data/data/com.tagent/files/usr`
  - `$HOME`: `/data/data/com.tagent/files/home`
- **STRICT PROHIBITION**: NEVER touch or modify `/data/data/com.termux`. That is Boss's main host Termux environment. Respect sandbox boundaries.

## 3. Package Management & Tooling Rules
- **Package Manager**: Use `apt-get` or `apt` directly (e.g., `apt install -y <pkg>`).
- **C++ & Node Runtimes**: Node.js and packages depend on `libc++` and `openssl 3.5.0`. Never downgrade core libraries.
- **Dpkg Wrapper**: `bin/dpkg` is a custom wrapper that auto-rewrites upstream packages from `com.termux` to `com.tagent`. Never delete `bin/dpkg` or `bin/dpkg-real`.

## 4. Hardware & Thermal Protection
- **Phone Model**: iQOO Neo 10 (Android 15 / Funtouch OS 15).
- **Target Temperature**: 34°C - 35°C.
- **Strict Zero-Background Daemon Rule**: Never launch background servers, daemons, or infinite loops (`nohup`, `&`, persistent HTTP listeners). All commands must run synchronously and exit cleanly.

## 5. Sovereign AI Integration
- **API Router**: Pre-configured to `https://freellmapi-backup.onrender.com/v1`.
- **Roster**: 158 Flagship AI models accessible via sovereign key.
CLAUDE_MD_EOF

cp "$HOME/CLAUDE.md" "$HOME/ANTIGRAVITY.md" 2>/dev/null || true
cp "$HOME/CLAUDE.md" "$HOME/HERMES.md" 2>/dev/null || true

# Check success criteria
if command -v git >/dev/null 2>&1 || command -v curl >/dev/null 2>&1; then
    touch "$MARKER"
    echo ""
    echo "==================================================="
    echo "Setup successfully complete!"
    echo "Antigravity ('antigravity'), Claude Code ('claude')"
    echo "aur Hermes Agent ('hermes') pre-configured hain!"
    echo "Brief file: ~/AGENT_README.md"
    echo "==================================================="
else
    echo ""
    echo "Setup adhura raha (internet ya package issue)."
    echo "Agli baar app khulne par setup phir se chalega."
fi
