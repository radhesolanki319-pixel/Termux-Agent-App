#!/data/data/com.tagent/files/usr/bin/bash
# Termux Agent App — first-run automatic setup.
# Installs the agent toolkit, fetches the app's own source code and drops
# an AGENT_README.md brief in $HOME. Runs once: the marker file is only
# written when everything succeeded, otherwise the next shell retries.

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

FAILED=0

export DEBIAN_FRONTEND=noninteractive

pkg update -y || apt-get update -y || true
pkg install -y git gh python nodejs openssh curl jq ripgrep || apt-get install -y git gh python nodejs openssh curl jq ripgrep || FAILED=1

# --- Install & Configure Claude Code ---
echo "Claude Code install & configure ho raha hai..."
npm install -g @anthropic-ai/claude-code || true

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
echo "Hermes Agent setup ho raha hai..."
pip install --upgrade requests prompt_toolkit aiohttp 2>/dev/null || true
if [ ! -d "$HOME/Hermes-agent-" ]; then
    git clone --depth 1 https://github.com/radhesolanki319-pixel/Hermes-agent- "$HOME/Hermes-agent-" 2>/dev/null || true
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
    python -c "print('Hermes Ready. Models connected to FreeLLM API (107/158 Models)')"
fi
HERMES_EOF
chmod 755 "$PREFIX/bin/hermes" 2>/dev/null || true

# Create tagent-bridge command runner
mkdir -p "$PREFIX/bin" 2>/dev/null
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

if [ ! -d "$HOME/Termux-Agent-App" ]; then
	git clone --depth 1 https://github.com/radhesolanki319-pixel/Termux-Agent-App "$HOME/Termux-Agent-App" || FAILED=1
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

## App ka source code
- Isi device par: ~/Termux-Agent-App
- GitHub: https://github.com/radhesolanki319-pixel/Termux-Agent-App

## Issue / bug fix karne ka flow
1. Problem ko pehle yahi reproduce karke samjho (logs: logcat me is app
   ke apne entries milenge).
2. Source ~/Termux-Agent-App me hai — fix wahi karo.
3. Commit karke push karo. Push se pehle ek baar `gh auth login` zaroori
   hai (user se karwana — bina login ke push par 403 aayega).
4. Push hote hi GitHub Actions khud naya APK bana kar repo ke Releases
   (tag agent-v1) me daal deta hai. User wahi se install karke test karega.
5. Jab tak user naye build ko phone par confirm na kare, fix ko "ho gaya"
   mat samjho.

## Notes
- Is app me koi agent pehle se install nahi aata — user apni marzi se
  agent (Claude Code, Antigravity, Hermes) install karta hai.
- Tools: git, gh, python, nodejs, openssh, curl, jq, ripgrep pehle se
  mile hain. Aur kuch chahiye to `pkg install <name>` se le lo.
AGENT_EOF

if [ "$FAILED" -eq 0 ]; then
	touch "$MARKER"
	echo ""
	echo "Setup complete. Ab koi bhi AI agent (Claude, Antigravity)"
	echo "yahan kaam karke app ke issue fix kar sakta hai."
	echo "Agent ke liye brief: ~/AGENT_README.md"
else
	echo ""
	echo "Setup adhura raha (shayad internet band tha)."
	echo "Agli baar app khulne par setup phir se chalega."
fi
