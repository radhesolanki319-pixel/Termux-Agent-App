# Termux Agent App: Connect screen par save kiye gaye provider settings
# (provider + API key) har login shell me load karo, taaki andar ke agents
# wahi provider/key use karein jo user ne app me chuna hai.
if [ -f "$HOME/.termux-agent/provider.env" ]; then
	. "$HOME/.termux-agent/provider.env"
fi
