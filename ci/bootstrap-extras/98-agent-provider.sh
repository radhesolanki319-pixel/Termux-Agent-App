# Termux Agent App: Connect screen par save kiye gaye provider settings
# (provider + API key) har login shell me load karo, taaki andar ke agents
# wahi provider/key use karein jo user ne app me chuna hai.
if [ -f "$HOME/.termux-agent/provider.env" ]; then
	. "$HOME/.termux-agent/provider.env"
fi

# Design: terminal ka card wala rang (gehara grey background) — sirf tab likho
# jab user ne apni colors.properties na banayi ho.
if [ ! -f "$HOME/.termux/colors.properties" ]; then
	mkdir -p "$HOME/.termux"
	printf 'background=#202024\nforeground=#F2F2F5\ncursor=#F2F2F5\n' > "$HOME/.termux/colors.properties"
fi
