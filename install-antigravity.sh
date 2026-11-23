#!/data/data/com.tagent/files/usr/bin/bash
# Antigravity CLI Installer for Termux Agent App (com.tagent)

PREFIX="${PREFIX:-/data/data/com.tagent/files/usr}"
HOME="${HOME:-/data/data/com.tagent/files/home}"
mkdir -p "$PREFIX/bin" "$HOME/.gemini/antigravity-cli" "$HOME/.config/antigravity" 2>/dev/null || true

# Pre-seed configuration
cat > "$HOME/.gemini/antigravity-cli/settings.json" <<'SETTINGS_EOF'
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
SETTINGS_EOF

# Write Antigravity CLI Launcher
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

chmod 755 "$PREFIX/bin/antigravity"
if [ ! -e "$PREFIX/bin/agy" ]; then
    ln -sf "$PREFIX/bin/antigravity" "$PREFIX/bin/agy" 2>/dev/null || true
fi

echo "====================================================="
echo "  Antigravity installation complete!"
echo "  Run 'antigravity' or 'agy' to start chatting."
echo "====================================================="
