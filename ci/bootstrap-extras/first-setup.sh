#!/data/data/com.tagent/files/usr/bin/bash
# Termux Agent App — first-run automatic setup.
# Installs the agent toolkit, fetches the app's own source code and drops
# an AGENT_README.md brief in $HOME. Runs once: the marker file is only
# written when everything succeeded, otherwise the next shell retries.

MARKER="$HOME/.termux-agent-setup.done"
LOCK="$HOME/.termux-agent-setup.lock"

if [ -e "$MARKER" ]; then
	exit 0
fi
if ! mkdir "$LOCK" 2>/dev/null; then
	# Another shell is already running the setup.
	exit 0
fi
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

echo ""
echo "==================================================="
echo "  Termux Agent App — pehla setup ho raha hai"
echo "  Agent toolkit install ho raha hai."
echo "  Internet chahiye, app band mat karna."
echo "==================================================="

FAILED=0

pkg update -y || FAILED=1
pkg install -y git gh python nodejs openssh curl jq ripgrep || FAILED=1

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
